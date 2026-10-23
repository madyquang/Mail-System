package com.mailsystem.server.service;

import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.MailDAO;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.session.SessionManager;

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

    enum Action { ADDED, REMOVED, DELETED, MEMBER_LEFT }

    private final MailDAO mailDAO;
    private final FolderDAO folderDAO;
    private final AccountDAO accountDAO;

    GroupNotifier(MailDAO mailDAO, FolderDAO folderDAO, AccountDAO accountDAO) {
        this.mailDAO = mailDAO;
        this.folderDAO = folderDAO;
        this.accountDAO = accountDAO;
    }

    /** actorId: người thực hiện (chủ nhóm, hoặc thành viên vừa rời); targetId: người được báo. */
    void notify(Action action, int groupId, String groupName, int actorId, int targetId, int memberCount) {
        try {
            Optional<Account> actor = accountDAO.findById(actorId);
            Optional<Account> target = accountDAO.findById(targetId);
            String actorName = actor.map(GroupNotifier::name).orElse("Chủ nhóm");
            String actorEmail = actor.map(Account::getEmail).orElse("");

            if (action != Action.MEMBER_LEFT) {
                String subject = subject(action, groupName);
                String body = body(action, groupName, actorName, actorEmail,
                        target.map(GroupNotifier::name).orElse("bạn"), memberCount);
                int mailId = deliver(actorId, targetId, subject, body);
                JsonObject mailEvent = new JsonObject();
                mailEvent.addProperty("mailId", mailId);
                mailEvent.addProperty("subject", subject);
                mailEvent.addProperty("senderEmail", actorEmail);
                mailEvent.addProperty("senderName", actorName);
                SessionManager.getInstance().pushEvent(targetId, new EventMessage(EventName.NEW_MAIL, mailEvent));
            }

            JsonObject data = new JsonObject();
            data.addProperty("action", action.name());
            data.addProperty("groupId", groupId);
            data.addProperty("groupName", groupName);
            data.addProperty("actorEmail", actorEmail);
            data.addProperty("actorName", actorName);
            SessionManager.getInstance().pushEvent(targetId, new EventMessage(EventName.GROUP_UPDATED, data));
        } catch (SQLException | RuntimeException error) {
            System.err.println("[GroupNotifier] Khong gui duoc thong bao " + action + " nhom " + groupName + ": " + error);
        }
    }

    /** Thư chỉ vào Hộp thư đến của người nhận, trong một giao dịch. */
    private int deliver(int senderId, int recipientId, String subject, String body) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inbox = folderDAO.findFolderIdByType(connection, recipientId, "INBOX")
                        .orElseThrow(() -> new SQLException("Tai khoan " + recipientId + " khong co INBOX"));
                int mailId = mailDAO.insertMail(connection, senderId, subject, body);
                mailDAO.insertMailRecipient(connection, mailId, recipientId, inbox, "TO", false);
                connection.commit();
                return mailId;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    static String subject(Action action, String groupName) {
        return switch (action) {
            case ADDED -> "Bạn đã được thêm vào nhóm \"" + groupName + "\"";
            case REMOVED -> "Bạn đã được xoá khỏi nhóm \"" + groupName + "\"";
            case DELETED -> "Nhóm \"" + groupName + "\" đã bị xoá";
            case MEMBER_LEFT -> "";
        };
    }

    static String body(Action action, String groupName, String actorName, String actorEmail, String targetName,
            int memberCount) {
        String who = actorName + (actorEmail.isBlank() || actorName.equals(actorEmail) ? "" : " (" + actorEmail + ")");
        String text = switch (action) {
            case ADDED -> who + " đã thêm bạn vào nhóm thư \"" + groupName + "\""
                    + (memberCount > 0 ? " (hiện có " + memberCount + " thành viên)" : "") + ".\n\n"
                    + "Từ bây giờ:\n"
                    + "  • Thư gửi tới nhóm \"" + groupName + "\" sẽ về Hộp thư đến của bạn.\n"
                    + "  • Bạn có thể gửi thư cho cả nhóm bằng cách nhập \"" + groupName + "\" vào ô Tới.\n\n"
                    + "Nếu không muốn tham gia, mở Nhóm thư (biểu tượng nhóm trên thanh trên cùng) và chọn Rời nhóm.";
            case REMOVED -> who + " đã xoá bạn khỏi nhóm thư \"" + groupName + "\".\n\n"
                    + "Bạn sẽ không còn nhận thư gửi tới nhóm này và không gửi thư cho nhóm được nữa. "
                    + "Các thư cũ của nhóm vẫn còn trong hộp thư của bạn.";
            case DELETED -> who + " đã xoá nhóm thư \"" + groupName + "\".\n\n"
                    + "Địa chỉ nhóm không còn dùng được. Các thư cũ của nhóm vẫn còn trong hộp thư của bạn.";
            case MEMBER_LEFT -> "";
        };
        return "Xin chào " + targetName + ",\n\n" + text + "\n\n— Thư thông báo tự động của Mail System";
    }

    private static String name(Account account) {
        return account.getDisplayName() == null || account.getDisplayName().isBlank()
                ? account.getEmail() : account.getDisplayName();
    }
}
