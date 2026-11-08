// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// JSON, tài khoản, EVENT.
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
// DAO dùng để ghi thư thông báo.
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.MailDAO;
// Kết nối CSDL (để chạy transaction) và đẩy EVENT.
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.session.SessionManager;

// JDBC và Optional.
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Báo cho người dùng biết khi tư cách thành viên nhóm của họ thay đổi.
 *
 * Hai kênh bổ sung cho nhau:
 * - THƯ THÔNG BÁO vào Hộp thư đến (gửi danh nghĩa chủ nhóm): người đang offline
 *   lần đăng nhập sau vẫn thấy, và có lưu lại để đọc.
 * - EVENT real-time: NEW_MAIL (Client hiện thông báo desktop như thư thường) và
 *   GROUP_UPDATED (cửa sổ Nhóm thư đang mở tự tải lại).
 *
 * Thư chỉ có dòng mail_recipient của người nhận, không có bản "Đã gửi" cho chủ
 * nhóm: thêm 30 thành viên không làm đầy mục Đã gửi của chủ nhóm 30 thư.
 * Thông báo là việc phụ: lỗi ở đây chỉ ghi log, không làm hỏng thao tác chính.
 */
final class GroupNotifier {

    // Loại thay đổi: được thêm, bị xoá khỏi nhóm, nhóm bị xoá, một thành viên tự rời nhóm.
    enum Action { ADDED, REMOVED, DELETED, MEMBER_LEFT }

    // Các DAO cần để ghi thư và đọc thông tin tài khoản.
    private final MailDAO mailDAO;
    private final FolderDAO folderDAO;
    private final AccountDAO accountDAO;

    // GroupService tạo GroupNotifier và truyền các DAO vào.
    GroupNotifier(MailDAO mailDAO, FolderDAO folderDAO, AccountDAO accountDAO) {
        this.mailDAO = mailDAO;
        this.folderDAO = folderDAO;
        this.accountDAO = accountDAO;
    }

    /** actorId: người thực hiện (chủ nhóm, hoặc thành viên vừa rời); targetId: người được báo. */
    void notify(Action action, int groupId, String groupName, String groupAddress, int actorId, int targetId,
            int memberCount) {
        try {
            // Thông tin người thực hiện và người nhận thông báo.
            Optional<Account> actor = accountDAO.findById(actorId);
            Optional<Account> target = accountDAO.findById(targetId);
            // Tên và email người thực hiện (không tìm thấy thì dùng giá trị mặc định).
            String actorName = actor.map(GroupNotifier::name).orElse("Chủ nhóm");
            String actorEmail = actor.map(Account::getEmail).orElse("");

            // MEMBER_LEFT (báo cho chủ nhóm) chỉ gửi EVENT, không gửi thư.
            if (action != Action.MEMBER_LEFT) {
                // Soạn tiêu đề và nội dung thư thông báo.
                String subject = subject(action, groupName);
                String body = body(action, groupName, groupAddress, actorName, actorEmail,
                        target.map(GroupNotifier::name).orElse("bạn"), memberCount);
                // Ghi thư vào Hộp thư đến của người nhận.
                int mailId = deliver(actorId, targetId, subject, body);
                // EVENT NEW_MAIL để Client hiện thông báo như thư thường.
                JsonObject mailEvent = new JsonObject();
                mailEvent.addProperty("mailId", mailId);
                mailEvent.addProperty("subject", subject);
                mailEvent.addProperty("senderEmail", actorEmail);
                mailEvent.addProperty("senderName", actorName);
                SessionManager.getInstance().pushEvent(targetId, new EventMessage(EventName.NEW_MAIL, mailEvent));
            }

            // EVENT GROUP_UPDATED để cửa sổ Nhóm thư tự tải lại.
            JsonObject data = new JsonObject();
            data.addProperty("action", action.name());
            data.addProperty("groupId", groupId);
            data.addProperty("groupName", groupName);
            // Nhóm cũ có thể chưa có địa chỉ.
            if (groupAddress != null) {
                data.addProperty("groupAddress", groupAddress);
            }
            data.addProperty("actorEmail", actorEmail);
            data.addProperty("actorName", actorName);
            SessionManager.getInstance().pushEvent(targetId, new EventMessage(EventName.GROUP_UPDATED, data));
        } catch (SQLException | RuntimeException error) {
            // Lỗi chỉ ghi log, thao tác chính (thêm/xoá thành viên) vẫn thành công.
            System.err.println("[GroupNotifier] Khong gui duoc thong bao " + action + " nhom " + groupName + ": " + error);
        }
    }

    /** Thư chỉ vào Hộp thư đến của người nhận, trong một giao dịch. */
    private int deliver(int senderId, int recipientId, String subject, String body) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Tắt tự commit: ghi mail + mail_recipient cùng một transaction.
            connection.setAutoCommit(false);
            try {
                // Hộp thư đến của người nhận.
                int inbox = folderDAO.findFolderIdByType(connection, recipientId, "INBOX")
                        .orElseThrow(() -> new SQLException("Tai khoan " + recipientId + " khong co INBOX"));
                // Ghi nội dung thư.
                int mailId = mailDAO.insertMail(connection, senderId, subject, body);
                // Chỉ một bản thư: của người nhận, vai trò TO, chưa đọc.
                mailDAO.insertMailRecipient(connection, mailId, recipientId, inbox, "TO", false);
                connection.commit();
                return mailId;
            } catch (SQLException error) {
                // Lỗi: huỷ toàn bộ transaction.
                connection.rollback();
                throw error;
            }
        }
    }

    /** Tiêu đề thư thông báo theo loại thay đổi. */
    static String subject(Action action, String groupName) {
        // switch dạng biểu thức (Java 14+): mỗi nhánh trả một giá trị.
        return switch (action) {
            case ADDED -> "Bạn đã được thêm vào nhóm \"" + groupName + "\"";
            case REMOVED -> "Bạn đã được xoá khỏi nhóm \"" + groupName + "\"";
            case DELETED -> "Nhóm \"" + groupName + "\" đã bị xoá";
            case MEMBER_LEFT -> "";
        };
    }

    /** Nội dung thư thông báo. */
    static String body(Action action, String groupName, String groupAddress, String actorName, String actorEmail,
            String targetName, int memberCount) {
        // Gửi tới nhóm bằng địa chỉ (ưu tiên) hoặc tên nhóm.
        String sendTo = groupAddress == null || groupAddress.isBlank() ? "\"" + groupName + "\"" : groupAddress;
        // "Tên (email)", hoặc chỉ tên nếu không có email / tên chính là email.
        String who = actorName + (actorEmail.isBlank() || actorName.equals(actorEmail) ? "" : " (" + actorEmail + ")");
        // Phần chính theo loại thay đổi.
        String text = switch (action) {
            case ADDED -> who + " đã thêm bạn vào nhóm thư \"" + groupName + "\""
                    + (memberCount > 0 ? " (hiện có " + memberCount + " thành viên)" : "") + ".\n\n"
                    + "Từ bây giờ:\n"
                    + "  • Thư gửi tới nhóm \"" + groupName + "\" sẽ về Hộp thư đến của bạn.\n"
                    + "  • Bạn có thể gửi thư cho cả nhóm bằng cách nhập " + sendTo + " vào ô Tới.\n\n"
                    + "Nếu không muốn tham gia, mở Nhóm thư (biểu tượng nhóm trên thanh trên cùng) và chọn Rời nhóm.";
            case REMOVED -> who + " đã xoá bạn khỏi nhóm thư \"" + groupName + "\".\n\n"
                    + "Bạn sẽ không còn nhận thư gửi tới nhóm này và không gửi thư cho nhóm được nữa. "
                    + "Các thư cũ của nhóm vẫn còn trong hộp thư của bạn.";
            case DELETED -> who + " đã xoá nhóm thư \"" + groupName + "\".\n\n"
                    + "Địa chỉ nhóm không còn dùng được. Các thư cũ của nhóm vẫn còn trong hộp thư của bạn.";
            case MEMBER_LEFT -> "";
        };
        // Ghép lời chào + nội dung + chữ ký.
        return "Xin chào " + targetName + ",\n\n" + text + "\n\n— Thư thông báo tự động của Mail System";
    }

    // Tên hiển thị; trống thì dùng email.
    private static String name(Account account) {
        return account.getDisplayName() == null || account.getDisplayName().isBlank()
                ? account.getEmail() : account.getDisplayName();
    }
}
