// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// DTO thư: tệp đính kèm, chi tiết thư, dòng tóm tắt trong danh sách.
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;
// Interface mà lớp này thực hiện; kết nối CSDL; nơi lưu tệp trên đĩa.
import com.mailsystem.server.dao.MailDAO;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.storage.AttachmentStorage;

// Vào/ra tệp.
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
// Các lớp JDBC.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
// Định dạng ngày giờ, tập hợp, Base64.
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Truy cập bảng mail, mail_recipient, attachment bằng JDBC. Mọi truy vấn đều lọc theo account_id. */
public class JdbcMailDAO implements MailDAO {

    // Định dạng ISO-8601, vd "2026-11-06T09:15:00".
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public int insertMail(int senderId, String subject, String body) throws SQLException {
        // Mượn kết nối riêng rồi gọi bản dùng kết nối.
        try (Connection connection = DatabaseConnection.getConnection()) {
            return insertMail(connection, senderId, subject, body);
        }
    }

    @Override
    public int insertMail(Connection connection, int senderId, String subject, String body) throws SQLException {
        // Thư mới không thuộc hội thoại nào: threadId và inReplyTo đều null.
        return insertMail(connection, senderId, subject, body, null, null);
    }

    @Override
    public int insertMail(Connection connection, int senderId, String subject, String body, Integer threadId,
            Integer inReplyTo) throws SQLException {
        // thread_id / in_reply_to có thể NULL (setObject với null sẽ ghi NULL).
        String sql = "INSERT INTO mail (sender_id, subject, body, thread_id, in_reply_to) VALUES (?, ?, ?, ?, ?)";
        // RETURN_GENERATED_KEYS: lấy mail_id tự tăng vừa tạo.
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // Gán 5 tham số theo thứ tự cột.
            statement.setInt(1, senderId);
            statement.setString(2, subject);
            statement.setString(3, body);
            statement.setObject(4, threadId);
            statement.setObject(5, inReplyTo);
            statement.executeUpdate();
            // Đọc khoá vừa tạo.
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        // Không nhận được khoá (bất thường).
        throw new SQLException("Database did not return the new mail ID");
    }

    @Override
    public void insertMailRecipient(int mailId, int accountId, int folderId, String recipientType, boolean isRead)
            throws SQLException {
        // Mượn kết nối riêng rồi gọi bản dùng kết nối.
        try (Connection connection = DatabaseConnection.getConnection()) {
            insertMailRecipient(connection, mailId, accountId, folderId, recipientType, isRead);
        }
    }

    @Override
    public void insertMailRecipient(Connection connection, int mailId, int accountId, int folderId,
            String recipientType, boolean isRead) throws SQLException {
        // Một bản thư: thư nào, của ai, ở thư mục nào, vai trò gì, đã đọc chưa.
        String sql = "INSERT INTO mail_recipient (mail_id, account_id, folder_id, recipient_type, is_read) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            statement.setInt(2, accountId);
            statement.setInt(3, folderId);
            statement.setString(4, recipientType);
            statement.setBoolean(5, isRead);
            // Chạy câu INSERT.
            statement.executeUpdate();
        }
    }

    @Override
    public void insertAttachment(int mailId, String filename, String mimeType, long sizeBytes, String storagePath)
            throws SQLException {
        // Mượn kết nối riêng rồi gọi bản dùng kết nối.
        try (Connection connection = DatabaseConnection.getConnection()) {
            insertAttachment(connection, mailId, filename, mimeType, sizeBytes, storagePath);
        }
    }

    @Override
    public void insertAttachment(Connection connection, int mailId, String filename, String mimeType,
            long sizeBytes, String storagePath) throws SQLException {
        // Chỉ lưu thông tin tệp; nội dung tệp nằm trên đĩa (storage_path).
        String sql = "INSERT INTO attachment (mail_id, filename, mime_type, size_bytes, storage_path) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            statement.setString(2, filename);
            statement.setString(3, mimeType);
            statement.setLong(4, sizeBytes);
            statement.setString(5, storagePath);
            statement.executeUpdate();
        }
    }

    /**
     * Cột chung cho mọi danh sách thư. snippet lấy 160 ký tự đầu của nội dung;
     * has_attachments dùng EXISTS (dừng ngay khi thấy 1 tệp, không đếm hết).
     * thread_id: COALESCE(thread_id, mail_id) = mã thư đầu hội thoại.
     */
    private static final String SUMMARY_SELECT = "SELECT m.mail_id, mr.entry_id, sender.email AS sender_email, "
            + "sender.display_name AS sender_name, m.subject, LEFT(m.body, 160) AS snippet, m.sent_at, "
            + "mr.is_read, mr.is_starred, f.folder_type, COALESCE(m.thread_id, m.mail_id) AS thread_id, "
            + "EXISTS(SELECT 1 FROM attachment a WHERE a.mail_id = m.mail_id) AS has_attachments "
            + "FROM mail_recipient mr "
            + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
            + "JOIN mail m ON m.mail_id = mr.mail_id "
            + "JOIN account sender ON sender.account_id = m.sender_id "
            + "WHERE mr.account_id = ? AND mr.is_deleted = FALSE ";
    // Mới nhất trước; cùng thời điểm thì bản thư tạo sau xếp trước.
    private static final String SUMMARY_ORDER = " ORDER BY m.sent_at DESC, mr.entry_id DESC";

    @Override
    public List<MailSummary> getMailList(int accountId, int folderId) throws SQLException {
        // Bản thư của tài khoản trong một thư mục.
        return querySummaries(SUMMARY_SELECT + "AND mr.folder_id = ?" + SUMMARY_ORDER, accountId, folderId);
    }

    @Override
    public List<MailSummary> getStarredList(int accountId) throws SQLException {
        // Thư gắn sao ở mọi thư mục trừ Thùng rác.
        return querySummaries(SUMMARY_SELECT + "AND mr.is_starred = TRUE AND f.folder_type <> 'TRASH'"
                + SUMMARY_ORDER, accountId);
    }

    // Chạy truy vấn danh sách với các tham số số nguyên theo thứ tự; mỗi dòng thành một MailSummary.
    private List<MailSummary> querySummaries(String sql, int... parameters) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // Gán tham số ?1, ?2, ... theo thứ tự.
            for (int i = 0; i < parameters.length; i++) {
                statement.setInt(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<MailSummary> summaries = new ArrayList<>();
                // Mỗi dòng kết quả thành một MailSummary.
                while (result.next()) {
                    summaries.add(readSummary(result));
                }
                return summaries;
            }
        }
    }

    @Override
    public Optional<MailDetail> getMailDetail(int mailId, int accountId, Integer entryId) throws SQLException {
        // Bản thư của tài khoản (có thể chỉ định entryId), kèm nội dung thư và thông tin người gửi.
        String sql = "SELECT mr.entry_id, mr.recipient_type, mr.is_starred, mr.is_read, COALESCE(m.thread_id, m.mail_id) AS thread_id, "
                + "m.sender_id, sender.email AS sender_email, "
                + "sender.display_name AS sender_name, m.subject, m.body, m.sent_at, f.folder_type "
                + "FROM mail_recipient mr JOIN mail m ON m.mail_id = mr.mail_id "
                + "JOIN account sender ON sender.account_id = m.sender_id "
                + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
                + "WHERE mr.mail_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE "
                + (entryId == null ? "" : "AND mr.entry_id = ? ")
                // Một tài khoản có thể có 2 bản của cùng thư (SENDER trong SENT và
                // bản nhận trong INBOX khi tự gửi cho mình): ưu tiên bản nhận.
                + "ORDER BY (mr.recipient_type = 'SENDER'), mr.entry_id LIMIT 1";
        // Đọc thư và đánh dấu đã đọc trong cùng một transaction.
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Tắt tự commit.
            connection.setAutoCommit(false);
            try {
                MailDetail detail;
                int openedEntryId;
                String recipientType;
                String folderType;
                int senderId;
                // Bước 1: đọc bản thư.
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    // ?1 mã thư, ?2 tài khoản, ?3 (nếu có) mã bản thư.
                    statement.setInt(1, mailId);
                    statement.setInt(2, accountId);
                    if (entryId != null) {
                        statement.setInt(3, entryId);
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        // Không tồn tại hoặc tài khoản không có quyền xem.
                        if (!result.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        // Ghi nhớ vài giá trị để dùng ở các bước sau.
                        openedEntryId = result.getInt("entry_id");
                        recipientType = result.getString("recipient_type");
                        folderType = result.getString("folder_type");
                        senderId = result.getInt("sender_id");
                        // Dựng MailDetail từ dòng kết quả.
                        detail = new MailDetail();
                        detail.setMailId(mailId);
                        detail.setEntryId(openedEntryId);
                        detail.setStarred(result.getBoolean("is_starred"));
                        detail.setFolderType(folderType);
                        detail.setThreadId(result.getInt("thread_id"));
                        // Lần đọc đầu tiên khi: chưa đọc, không ở Thùng rác, không phải bản của người gửi (dùng để gửi READ_RECEIPT).
                        detail.setNewlyRead(!result.getBoolean("is_read") && !"TRASH".equalsIgnoreCase(folderType)
                                && !"SENDER".equals(recipientType));
                        detail.setSenderAccountId(result.getInt("sender_id"));
                        detail.setSenderEmail(result.getString("sender_email"));
                        detail.setSenderName(result.getString("sender_name"));
                        detail.setSubject(result.getString("subject"));
                        detail.setBody(result.getString("body"));
                        detail.setSentAt(formatDateTime(result.getObject("sent_at", java.time.LocalDateTime.class)));
                    }
                }

                // Người xem có phải người gửi không.
                boolean isSender = accountId == senderId || "SENDER".equals(recipientType);
                boolean isBccRecipient = "BCC".equals(recipientType);
                // Bước 2: danh sách To / Cc / Bcc (Bcc chỉ hiện theo quyền).
                loadRecipients(connection, detail, mailId, accountId, isSender, isBccRecipient);
                // Người gửi được xem ai đã đọc thư.
                if (isSender) {
                    detail.setReadReceipts(loadReadReceipts(connection, mailId, accountId));
                }
                // Bước 3: thông tin các tệp đính kèm.
                detail.setAttachments(loadAttachments(connection, mailId));
                // Bước 4: đánh dấu đã đọc (không áp dụng trong Thùng rác); read_at chỉ ghi lần đầu nhờ COALESCE.
                if (!"TRASH".equalsIgnoreCase(folderType)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE mail_recipient SET is_read = TRUE, read_at = COALESCE(read_at, NOW()) "
                                    + "WHERE entry_id = ? AND account_id = ?")) {
                        statement.setInt(1, openedEntryId);
                        statement.setInt(2, accountId);
                        statement.executeUpdate();
                    }
                }
                // Lưu thay đổi.
                connection.commit();
                return Optional.of(detail);
            } catch (SQLException error) {
                // Lỗi: huỷ transaction rồi ném lỗi tiếp.
                connection.rollback();
                throw error;
            }
        }
    }

    @Override
    public List<Attachment> getAttachmentsByMailId(int mailId) throws SQLException {
        // Mượn kết nối rồi đọc.
        try (Connection connection = DatabaseConnection.getConnection()) {
            return loadAttachments(connection, mailId);
        }
    }

    @Override
    public Optional<Attachment> getAttachmentChunk(
            int attachmentId, int accountId, long offset, int maxBytes) throws SQLException {
        // Chỉ đọc được tệp khi tài khoản còn giữ một bản thư chứa tệp đó (kiểm tra quyền ngay trong SQL).
        String sql = "SELECT a.attachment_id, a.filename, a.mime_type, a.size_bytes, a.storage_path "
                + "FROM attachment a "
                + "JOIN mail_recipient mr ON mr.mail_id = a.mail_id "
                + "WHERE a.attachment_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE LIMIT 1";
        // Vị trí hoặc độ dài không hợp lệ.
        if (offset < 0 || maxBytes <= 0) {
            throw new SQLException("Invalid attachment chunk range.");
        }
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1 mã tệp, ?2 tài khoản.
            statement.setInt(1, attachmentId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                // Không có quyền hoặc không tồn tại.
                if (!result.next()) {
                    return Optional.empty();
                }
                // Thông tin tệp.
                Attachment attachment = new Attachment();
                attachment.setAttachmentId(result.getInt("attachment_id"));
                attachment.setFilename(result.getString("filename"));
                attachment.setMimeType(result.getString("mime_type"));
                attachment.setSizeBytes(result.getLong("size_bytes"));

                // Đường dẫn thật trên đĩa và kích thước thật.
                Path storedPath = AttachmentStorage.resolve(result.getString("storage_path"));
                long storedSize = Files.size(storedPath);
                // Kích thước trên đĩa phải khớp CSDL và không quá 25 MB (phát hiện tệp bị sửa / hỏng).
                if (storedSize != attachment.getSizeBytes() || storedSize > 25L * 1024 * 1024) {
                    throw new SQLException("Attachment size does not match stored metadata.");
                }
                // Vị trí bắt đầu nằm sau cuối tệp.
                if (offset > storedSize) {
                    throw new SQLException("Attachment chunk starts beyond end of file.");
                }
                // Số byte đọc lần này: tối đa maxBytes, không vượt cuối tệp.
                int length = (int) Math.min(maxBytes, storedSize - offset);
                byte[] content;
                // Mở tệp, bỏ qua offset byte đầu, đọc length byte.
                try (InputStream input = Files.newInputStream(storedPath)) {
                    input.skipNBytes(offset);
                    content = input.readNBytes(length);
                }
                // Đọc thiếu nghĩa là tệp vừa bị thay đổi.
                if (content.length != length) {
                    throw new SQLException("Attachment changed while being read.");
                }
                // Mã hoá Base64 để gửi trong JSON.
                attachment.setDataBase64(Base64.getEncoder().encodeToString(content));
                return Optional.of(attachment);
            // Lỗi đọc tệp: gói thành SQLException để nơi gọi xử lý chung.
            } catch (IOException error) {
                throw new SQLException("Could not read attachment content.", error);
            }

        }
    }

    @Override
    public boolean setRead(int entryId, int accountId, boolean read) throws SQLException {
        // Đặt cờ is_read của bản thư.
        return updateFlag("is_read", entryId, accountId, read);
    }

    @Override
    public boolean setStarred(int entryId, int accountId, boolean starred) throws SQLException {
        // Đặt cờ is_starred của bản thư.
        return updateFlag("is_starred", entryId, accountId, starred);
    }

    /**
     * Đặt một cờ boolean trên "bản thư của tôi". Trả true nếu bản thư thuộc tài
     * khoản (kể cả khi giá trị không đổi, vì MySQL báo 0 dòng bị ảnh hưởng).
     */
    private boolean updateFlag(String column, int entryId, int accountId, boolean value) throws SQLException {
        // Tên cột chỉ là hằng trong code (is_read / is_starred), không lấy từ Client nên ghép chuỗi an toàn.
        String sql = "UPDATE mail_recipient SET " + column + " = ? "
                + "WHERE entry_id = ? AND account_id = ? AND is_deleted = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1 giá trị, ?2 bản thư, ?3 tài khoản.
            statement.setBoolean(1, value);
            statement.setInt(2, entryId);
            statement.setInt(3, accountId);
            // Có dòng bị sửa: thành công.
            if (statement.executeUpdate() > 0) {
                return true;
            }
        }

        // 0 dòng bị sửa: hoặc giá trị không đổi, hoặc bản thư không thuộc tài khoản -> kiểm tra bản thư có tồn tại không.
        String existsSql = "SELECT 1 FROM mail_recipient "
                + "WHERE entry_id = ? AND account_id = ? AND is_deleted = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(existsSql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public boolean moveMailToTrash(int entryId, int accountId) throws SQLException {
        // Chuyển vào Thùng rác, nhớ thư mục cũ (original_folder_id) để khôi phục; bỏ qua nếu đã ở Thùng rác.
        String updateSql = "UPDATE mail_recipient mr "
                + "JOIN folder trash ON trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                + "SET mr.original_folder_id = mr.folder_id, mr.folder_id = trash.folder_id "
                + "WHERE mr.entry_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE "
                + "AND mr.folder_id <> trash.folder_id";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
            // Đã chuyển.
            if (statement.executeUpdate() > 0) {
                return true;
            }
        }

        // Không chuyển được: nếu bản thư đã nằm sẵn trong Thùng rác thì vẫn coi là thành công.
        String alreadyInTrashSql = "SELECT 1 FROM mail_recipient mr "
                + "JOIN folder trash ON trash.folder_id = mr.folder_id "
                + "AND trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                + "WHERE mr.entry_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(alreadyInTrashSql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public boolean deleteTrashMail(int entryId, int accountId) throws SQLException {
        // Tệp cần xoá trên đĩa (chỉ xoá sau khi commit thành công).
        List<Path> filesToDelete = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Xoá bản thư và (nếu cần) thư gốc trong một transaction.
            connection.setAutoCommit(false);
            try {
                // Bản thư phải đang nằm trong Thùng rác của tài khoản (dòng được khoá).
                Integer mailId = findTrashMailId(connection, entryId, accountId);
                if (mailId == null) {
                    connection.rollback();
                    return false;
                }
                // Xoá hẳn bản thư của tài khoản.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM mail_recipient WHERE entry_id = ? AND account_id = ?")) {
                    statement.setInt(1, entryId);
                    statement.setInt(2, accountId);
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                // Không còn ai giữ thư thì xoá luôn thư gốc và ghi lại tệp cần xoá.
                deleteMailIfUnreferenced(connection, mailId, filesToDelete);
                connection.commit();
            } catch (SQLException error) {
                rollback(connection, error);
                throw error;
            }
        }
        // Xoá tệp trên đĩa sau khi CSDL đã commit.
        deleteAttachmentFiles(filesToDelete);
        return true;
    }

    @Override
    public int emptyTrash(int accountId) throws SQLException {
        // Tệp cần xoá, các mail_id liên quan, số bản thư đã xoá.
        List<Path> filesToDelete = new ArrayList<>();
        Set<Integer> mailIds = new LinkedHashSet<>();
        int deletedEntries = 0;
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Mọi việc trong một transaction.
            connection.setAutoCommit(false);
            try {
                // Mọi bản thư trong Thùng rác của tài khoản (khoá các dòng).
                String findEntriesSql = "SELECT mr.entry_id, mr.mail_id "
                        + "FROM mail_recipient mr "
                        + "JOIN folder trash ON trash.folder_id = mr.folder_id "
                        + "AND trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                        + "WHERE mr.account_id = ? ORDER BY mr.mail_id, mr.entry_id FOR UPDATE";
                List<Integer> entryIds = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(findEntriesSql)) {
                    statement.setInt(1, accountId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            entryIds.add(result.getInt("entry_id"));
                            mailIds.add(result.getInt("mail_id"));
                        }
                    }
                }
                // Xoá từng bản thư bằng một lô lệnh.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM mail_recipient WHERE entry_id = ? AND account_id = ?")) {
                    for (int entryId : entryIds) {
                        statement.setInt(1, entryId);
                        statement.setInt(2, accountId);
                        statement.addBatch();
                    }
                    int[] results = statement.executeBatch();
                    // Đếm số lệnh thành công (SUCCESS_NO_INFO: driver không báo số dòng nhưng lệnh đã chạy).
                    for (int result : results) {
                        if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                            deletedEntries++;
                        }
                    }
                }
                // Thư gốc nào không còn ai giữ thì xoá.
                for (int mailId : mailIds) {
                    deleteMailIfUnreferenced(connection, mailId, filesToDelete);
                }
                connection.commit();
            } catch (SQLException error) {
                rollback(connection, error);
                throw error;
            }
        }
        // Xoá tệp trên đĩa sau khi CSDL đã commit.
        deleteAttachmentFiles(filesToDelete);
        return deletedEntries;
    }

    @Override
    public boolean restoreTrashMail(int entryId, int accountId) throws SQLException {
        // Khôi phục về thư mục cũ (original); không còn thì về thư mục mặc định theo vai trò
        // (fallback: người gửi về SENT, người nhận về INBOX). Chỉ áp dụng cho bản thư đang ở Thùng rác.
        String sql = "UPDATE mail_recipient mr "
                + "JOIN folder trash ON trash.folder_id = mr.folder_id "
                + "AND trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                + "LEFT JOIN folder original ON original.folder_id = mr.original_folder_id "
                + "AND original.account_id = mr.account_id AND original.folder_type <> 'TRASH' "
                + "LEFT JOIN folder fallback ON fallback.account_id = mr.account_id "
                + "AND fallback.folder_type = CASE WHEN mr.recipient_type = 'SENDER' THEN 'SENT' ELSE 'INBOX' END "
                + "SET mr.folder_id = COALESCE(original.folder_id, fallback.folder_id), "
                + "mr.original_folder_id = NULL "
                + "WHERE mr.entry_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE "
                + "AND COALESCE(original.folder_id, fallback.folder_id) IS NOT NULL";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
            return statement.executeUpdate() > 0;
        }
    }

    // Mã thư của bản thư nếu bản thư đang ở Thùng rác của tài khoản; khoá dòng (FOR UPDATE).
    private Integer findTrashMailId(Connection connection, int entryId, int accountId) throws SQLException {
        String sql = "SELECT mr.mail_id FROM mail_recipient mr "
                + "JOIN folder trash ON trash.folder_id = mr.folder_id "
                + "AND trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                + "WHERE mr.entry_id = ? AND mr.account_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("mail_id") : null;
            }
        }
    }

    // Xoá thư gốc nếu không còn bản thư nào trỏ tới; ghi lại các tệp đính kèm cần xoá.
    private void deleteMailIfUnreferenced(Connection connection, int mailId, List<Path> filesToDelete)
            throws SQLException {
        // Khoá dòng thư gốc: hai người cùng xoá bản thư cuối không chạy chồng lên nhau.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT mail_id FROM mail WHERE mail_id = ? FOR UPDATE")) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                // Thư gốc đã bị xoá trước đó.
                if (!result.next()) {
                    return;
                }
            }
        }
        // Còn người giữ bản thư thì giữ lại thư gốc.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM mail_recipient WHERE mail_id = ? LIMIT 1")) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }

        // Không còn ai: gom đường dẫn tệp để xoá sau khi commit.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT storage_path FROM attachment WHERE mail_id = ?")) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    filesToDelete.add(AttachmentStorage.resolve(result.getString("storage_path")));
                }
            }
        }
        // Xoá thư gốc (các dòng attachment bị xoá theo nhờ ON DELETE CASCADE).
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM mail WHERE mail_id = ?")) {
            statement.setInt(1, mailId);
            statement.executeUpdate();
        }
    }

    // Xoá các tệp trên đĩa; lỗi chỉ ghi log.
    private void deleteAttachmentFiles(List<Path> filesToDelete) {
        for (Path file : filesToDelete) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException error) {
                System.err.println("[JdbcMailDAO] KhÃ´ng xÃ³a Ä‘Æ°á»£c tá»‡p Ä‘Ã­nh kÃ¨m orphan "
                        + file + ": " + error.getMessage());
            }
        }
    }

    // Rollback; nếu rollback cũng lỗi thì đính kèm vào lỗi gốc.
    private void rollback(Connection connection, SQLException cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }

    @Override
    public List<MailSummary> searchMail(int accountId, Integer folderId, String keyword, String fromFilter)
            throws SQLException {
        // Bắt đầu từ câu SELECT chung (đã có điều kiện account_id), nối thêm điều kiện tuỳ chọn.
        String sql = SUMMARY_SELECT;
        // Từ khoá: tìm trong tiêu đề hoặc nội dung.
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (m.subject LIKE ? ESCAPE '!' OR m.body LIKE ? ESCAPE '!')";
        }
        // Lọc người gửi: theo email hoặc tên hiển thị.
        if (fromFilter != null && !fromFilter.isBlank()) {
            sql += " AND (sender.email LIKE ? ESCAPE '!' OR sender.display_name LIKE ? ESCAPE '!')";
        }
        // Giới hạn trong một thư mục.
        if (folderId != null) {
            sql += " AND mr.folder_id = ?";
        }
        // Thêm thứ tự sắp xếp.
        sql += SUMMARY_ORDER;
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // Gán tham số theo đúng thứ tự các điều kiện đã nối ở trên.
            int parameter = 1;
            statement.setInt(parameter++, accountId);
            if (keyword != null && !keyword.isBlank()) {
                String pattern = searchPattern(keyword);
                statement.setString(parameter++, pattern);
                statement.setString(parameter++, pattern);
            }
            if (fromFilter != null && !fromFilter.isBlank()) {
                String pattern = searchPattern(fromFilter);
                statement.setString(parameter++, pattern);
                statement.setString(parameter++, pattern);
            }
            if (folderId != null) {
                statement.setInt(parameter, folderId);
            }
            // Mỗi dòng thành một MailSummary.
            try (ResultSet result = statement.executeQuery()) {
                List<MailSummary> summaries = new ArrayList<>();
                while (result.next()) {
                    summaries.add(readSummary(result));
                }
                return summaries;
            }
        }
    }

    // Escape ký tự đặc biệt của LIKE (ký tự escape là '!') rồi bọc trong %...%.
    private String searchPattern(String value) {
        String escaped = value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    @Override
    public Optional<StoredAttachment> findAccessibleAttachment(int attachmentId, int accountId) throws SQLException {
        // Tệp chỉ dùng được khi tài khoản còn giữ một bản thư chứa tệp đó.
        String sql = "SELECT a.filename, a.mime_type, a.size_bytes, a.storage_path FROM attachment a "
                + "WHERE a.attachment_id = ? AND EXISTS (SELECT 1 FROM mail_recipient mr "
                + "WHERE mr.mail_id = a.mail_id AND mr.account_id = ? AND mr.is_deleted = FALSE)";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, attachmentId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredAttachment(result.getString("filename"), result.getString("mime_type"),
                        result.getLong("size_bytes"), AttachmentStorage.resolve(result.getString("storage_path"))));
            }
        }
    }

    /** Ai đã mở thư (chỉ người nhận bật chia sẻ báo đã đọc). */
    private List<com.mailsystem.common.model.ReadReceipt> loadReadReceipts(Connection connection, int mailId,
            int senderId) throws SQLException {
        // Người nhận (không tính chính người gửi) đã mở thư và cho phép chia sẻ, theo thứ tự thời gian đọc.
        String sql = "SELECT a.email, a.display_name, mr.read_at FROM mail_recipient mr "
                + "JOIN account a ON a.account_id = mr.account_id "
                + "WHERE mr.mail_id = ? AND mr.recipient_type IN ('TO','CC','BCC') AND mr.read_at IS NOT NULL "
                + "AND a.share_read_receipts = TRUE AND mr.account_id <> ? ORDER BY mr.read_at";
        List<com.mailsystem.common.model.ReadReceipt> receipts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            statement.setInt(2, senderId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    receipts.add(new com.mailsystem.common.model.ReadReceipt(result.getString("email"),
                            result.getString("display_name"),
                            formatDateTime(result.getObject("read_at", java.time.LocalDateTime.class))));
                }
            }
        }
        return receipts;
    }

    @Override
    public boolean moveToFolder(int entryId, int accountId, int folderId) throws SQLException {
        // Chỉ chuyển tới thư mục của CHÍNH tài khoản và không phải Thùng rác (xoá dùng DELETE_MAIL).
        String sql = "UPDATE mail_recipient mr "
                + "JOIN folder target ON target.folder_id = ? AND target.account_id = mr.account_id "
                + "AND target.folder_type <> 'TRASH' "
                + "SET mr.folder_id = target.folder_id, mr.original_folder_id = NULL "
                + "WHERE mr.entry_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1 thư mục đích, ?2 bản thư, ?3 tài khoản.
            statement.setInt(1, folderId);
            statement.setInt(2, entryId);
            statement.setInt(3, accountId);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public List<int[]> findThreadEntries(int accountId, int threadId) throws SQLException {
        // Mỗi thư một dòng (tự gửi cho mình có 2 bản: ưu tiên bản nhận), cũ trước mới sau, bỏ Thùng rác.
        String sql = "SELECT mr.mail_id, mr.entry_id FROM mail_recipient mr "
                + "JOIN mail m ON m.mail_id = mr.mail_id "
                + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
                + "WHERE mr.account_id = ? AND COALESCE(m.thread_id, m.mail_id) = ? AND mr.is_deleted = FALSE "
                + "AND f.folder_type <> 'TRASH' "
                + "ORDER BY m.sent_at, m.mail_id, (mr.recipient_type = 'SENDER')";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, threadId);
            try (ResultSet result = statement.executeQuery()) {
                List<int[]> entries = new ArrayList<>();
                // seenMails: mỗi thư chỉ lấy một bản (bản đầu tiên theo thứ tự đã sắp).
                Set<Integer> seenMails = new LinkedHashSet<>();
                while (result.next()) {
                    if (seenMails.add(result.getInt("mail_id"))) {
                        entries.add(new int[] {result.getInt("mail_id"), result.getInt("entry_id")});
                    }
                }
                return entries;
            }
        }
    }

    @Override
    public Optional<Integer> findThreadRoot(int mailId, int accountId) throws SQLException {
        // Mã hội thoại của thư (thread_id, hoặc chính mail_id), chỉ khi tài khoản có bản thư đó.
        String sql = "SELECT COALESCE(m.thread_id, m.mail_id) AS thread_id FROM mail m WHERE m.mail_id = ? "
                + "AND EXISTS (SELECT 1 FROM mail_recipient mr WHERE mr.mail_id = m.mail_id AND mr.account_id = ? "
                + "AND mr.is_deleted = FALSE)";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getInt("thread_id")) : Optional.empty();
            }
        }
    }

    @Override
    public List<Integer> getRecipientAccountIds(int mailId) throws SQLException {
        // Mọi người nhận (không tính SENDER), không trùng.
        String sql = "SELECT DISTINCT account_id FROM mail_recipient "
                + "WHERE mail_id = ? AND recipient_type IN ('TO', 'CC', 'BCC')";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                List<Integer> accountIds = new ArrayList<>();
                while (result.next()) {
                    accountIds.add(result.getInt("account_id"));
                }
                return accountIds;
            }
        }
    }

    // Đọc danh sách To / Cc / Bcc của thư.
    // Bcc: người gửi thấy đủ; người nhận Bcc chỉ thấy chính mình; người khác không thấy.
    private void loadRecipients(Connection connection, MailDetail detail, int mailId, int accountId,
            boolean isSender, boolean isBccRecipient) throws SQLException {
        String sql = "SELECT mr.account_id, mr.recipient_type, a.email FROM mail_recipient mr "
                + "JOIN account a ON a.account_id = mr.account_id "
                + "WHERE mr.mail_id = ? AND mr.recipient_type IN ('TO', 'CC', 'BCC') ORDER BY mr.entry_id";
        List<String> to = new ArrayList<>();
        List<String> cc = new ArrayList<>();
        List<String> bcc = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String type = result.getString("recipient_type");
                    String email = result.getString("email");
                    // Chia vào đúng danh sách theo vai trò.
                    switch (type) {
                        case "TO" -> to.add(email);
                        case "CC" -> cc.add(email);
                        case "BCC" -> {
                            if (isSender || (isBccRecipient && result.getInt("account_id") == accountId)) {
                                bcc.add(email);
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        // Gán vào MailDetail.
        detail.setTo(to);
        detail.setCc(cc);
        detail.setBcc(bcc);
    }

    // Thông tin các tệp đính kèm (không kèm nội dung).
    private List<Attachment> loadAttachments(Connection connection, int mailId) throws SQLException {
        String sql = "SELECT attachment_id, filename, mime_type, size_bytes FROM attachment WHERE mail_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                List<Attachment> attachments = new ArrayList<>();
                while (result.next()) {
                    Attachment attachment = new Attachment();
                    attachment.setAttachmentId(result.getInt("attachment_id"));
                    attachment.setFilename(result.getString("filename"));
                    attachment.setMimeType(result.getString("mime_type"));
                    attachment.setSizeBytes(result.getLong("size_bytes"));
                    attachments.add(attachment);
                }
                return attachments;
            }
        }
    }

    // Đọc dòng hiện tại thành MailSummary.
    private MailSummary readSummary(ResultSet result) throws SQLException {
        MailSummary summary = new MailSummary();
        summary.setMailId(result.getInt("mail_id"));
        summary.setEntryId(result.getInt("entry_id"));
        summary.setSenderEmail(result.getString("sender_email"));
        summary.setSenderName(result.getString("sender_name"));
        summary.setSubject(result.getString("subject"));
        // Đoạn trích: gộp mọi khoảng trắng / xuống dòng thành một dấu cách.
        String snippet = result.getString("snippet");
        summary.setSnippet(snippet == null ? "" : snippet.replaceAll("\\s+", " ").trim());
        summary.setSentAt(formatDateTime(result.getObject("sent_at", java.time.LocalDateTime.class)));
        summary.setRead(result.getBoolean("is_read"));
        summary.setStarred(result.getBoolean("is_starred"));
        summary.setHasAttachments(result.getBoolean("has_attachments"));
        summary.setFolderType(result.getString("folder_type"));
        summary.setThreadId(result.getInt("thread_id"));
        return summary;
    }

    /**
     * Đọc DATETIME thành LocalDateTime nguyên trạng (không quy đổi múi giờ).
     * getTimestamp() + serverTimezone=UTC từng làm giờ hiển thị lệch +7 tiếng vì
     * MySQL lưu giờ địa phương còn driver lại coi đó là giờ UTC.
     */
    private String formatDateTime(java.time.LocalDateTime time) {
        return time == null ? null : time.format(ISO_DATE_TIME);
    }
}
