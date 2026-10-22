package com.mailsystem.server.dao.jdbc;

import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.server.dao.MailDAO;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.storage.AttachmentStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JdbcMailDAO implements MailDAO {

    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public int insertMail(int senderId, String subject, String body) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return insertMail(connection, senderId, subject, body);
        }
    }

    @Override
    public int insertMail(Connection connection, int senderId, String subject, String body) throws SQLException {
        return insertMail(connection, senderId, subject, body, null, null);
    }

    @Override
    public int insertMail(Connection connection, int senderId, String subject, String body, Integer threadId,
            Integer inReplyTo) throws SQLException {
        String sql = "INSERT INTO mail (sender_id, subject, body, thread_id, in_reply_to) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, senderId);
            statement.setString(2, subject);
            statement.setString(3, body);
            statement.setObject(4, threadId);
            statement.setObject(5, inReplyTo);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Database did not return the new mail ID");
    }

    @Override
    public void insertMailRecipient(int mailId, int accountId, int folderId, String recipientType, boolean isRead)
            throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            insertMailRecipient(connection, mailId, accountId, folderId, recipientType, isRead);
        }
    }

    @Override
    public void insertMailRecipient(Connection connection, int mailId, int accountId, int folderId,
            String recipientType, boolean isRead) throws SQLException {
        String sql = "INSERT INTO mail_recipient (mail_id, account_id, folder_id, recipient_type, is_read) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, mailId);
            statement.setInt(2, accountId);
            statement.setInt(3, folderId);
            statement.setString(4, recipientType);
            statement.setBoolean(5, isRead);
            statement.executeUpdate();
        }
    }

    @Override
    public void insertAttachment(int mailId, String filename, String mimeType, long sizeBytes, String storagePath)
            throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            insertAttachment(connection, mailId, filename, mimeType, sizeBytes, storagePath);
        }
    }

    @Override
    public void insertAttachment(Connection connection, int mailId, String filename, String mimeType,
            long sizeBytes, String storagePath) throws SQLException {
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
     * Cá»™t chung cho má»i danh sÃ¡ch thÆ°. snippet láº¥y 160 kÃ½ tá»± Ä‘áº§u cá»§a ná»™i dung;
     * has_attachments dÃ¹ng EXISTS (dá»«ng ngay khi tháº¥y 1 tá»‡p, khÃ´ng Ä‘áº¿m háº¿t).
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
    private static final String SUMMARY_ORDER = " ORDER BY m.sent_at DESC, mr.entry_id DESC";

    @Override
    public List<MailSummary> getMailList(int accountId, int folderId) throws SQLException {
        return querySummaries(SUMMARY_SELECT + "AND mr.folder_id = ?" + SUMMARY_ORDER, accountId, folderId);
    }

    @Override
    public List<MailSummary> getStarredList(int accountId) throws SQLException {
        return querySummaries(SUMMARY_SELECT + "AND mr.is_starred = TRUE AND f.folder_type <> 'TRASH'"
                + SUMMARY_ORDER, accountId);
    }

    private List<MailSummary> querySummaries(String sql, int... parameters) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setInt(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<MailSummary> summaries = new ArrayList<>();
                while (result.next()) {
                    summaries.add(readSummary(result));
                }
                return summaries;
            }
        }
    }

    @Override
    public Optional<MailDetail> getMailDetail(int mailId, int accountId, Integer entryId) throws SQLException {
        String sql = "SELECT mr.entry_id, mr.recipient_type, mr.is_starred, mr.is_read, COALESCE(m.thread_id, m.mail_id) AS thread_id, "
                + "m.sender_id, sender.email AS sender_email, "
                + "sender.display_name AS sender_name, m.subject, m.body, m.sent_at, f.folder_type "
                + "FROM mail_recipient mr JOIN mail m ON m.mail_id = mr.mail_id "
                + "JOIN account sender ON sender.account_id = m.sender_id "
                + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
                + "WHERE mr.mail_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE "
                + (entryId == null ? "" : "AND mr.entry_id = ? ")
                // Má»™t tÃ i khoáº£n cÃ³ thá»ƒ cÃ³ 2 báº£n cá»§a cÃ¹ng thÆ° (SENDER trong SENT vÃ 
                // báº£n nháº­n trong INBOX khi tá»± gá»­i cho mÃ¬nh): Æ°u tiÃªn báº£n nháº­n.
                + "ORDER BY (mr.recipient_type = 'SENDER'), mr.entry_id LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MailDetail detail;
                int openedEntryId;
                String recipientType;
                String folderType;
                int senderId;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, mailId);
                    statement.setInt(2, accountId);
                    if (entryId != null) {
                        statement.setInt(3, entryId);
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        openedEntryId = result.getInt("entry_id");
                        recipientType = result.getString("recipient_type");
                        folderType = result.getString("folder_type");
                        senderId = result.getInt("sender_id");
                        detail = new MailDetail();
                        detail.setMailId(mailId);
                        detail.setEntryId(openedEntryId);
                        detail.setStarred(result.getBoolean("is_starred"));
                        detail.setFolderType(folderType);
                        detail.setThreadId(result.getInt("thread_id"));
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

                boolean isSender = accountId == senderId || "SENDER".equals(recipientType);
                boolean isBccRecipient = "BCC".equals(recipientType);
                loadRecipients(connection, detail, mailId, accountId, isSender, isBccRecipient);
                if (isSender) {
                    detail.setReadReceipts(loadReadReceipts(connection, mailId, accountId));
                }
                detail.setAttachments(loadAttachments(connection, mailId));
                if (!"TRASH".equalsIgnoreCase(folderType)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE mail_recipient SET is_read = TRUE, read_at = COALESCE(read_at, NOW()) "
                                    + "WHERE entry_id = ? AND account_id = ?")) {
                        statement.setInt(1, openedEntryId);
                        statement.setInt(2, accountId);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return Optional.of(detail);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    @Override
    public List<Attachment> getAttachmentsByMailId(int mailId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            return loadAttachments(connection, mailId);
        }
    }

    @Override
    public Optional<Attachment> getAttachmentChunk(
            int attachmentId, int accountId, long offset, int maxBytes) throws SQLException {
        String sql = "SELECT a.attachment_id, a.filename, a.mime_type, a.size_bytes, a.storage_path "
                + "FROM attachment a "
                + "JOIN mail_recipient mr ON mr.mail_id = a.mail_id "
                + "WHERE a.attachment_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE LIMIT 1";
        if (offset < 0 || maxBytes <= 0) {
            throw new SQLException("Invalid attachment chunk range.");
        }
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, attachmentId);
            statement.setInt(2, accountId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Attachment attachment = new Attachment();
                attachment.setAttachmentId(result.getInt("attachment_id"));
                attachment.setFilename(result.getString("filename"));
                attachment.setMimeType(result.getString("mime_type"));
                attachment.setSizeBytes(result.getLong("size_bytes"));

                Path storedPath = AttachmentStorage.resolve(result.getString("storage_path"));
                long storedSize = Files.size(storedPath);
                if (storedSize != attachment.getSizeBytes() || storedSize > 25L * 1024 * 1024) {
                    throw new SQLException("Attachment size does not match stored metadata.");
                }
                if (offset > storedSize) {
                    throw new SQLException("Attachment chunk starts beyond end of file.");
                }
                int length = (int) Math.min(maxBytes, storedSize - offset);
                byte[] content;
                try (InputStream input = Files.newInputStream(storedPath)) {
                    input.skipNBytes(offset);
                    content = input.readNBytes(length);
                }
                if (content.length != length) {
                    throw new SQLException("Attachment changed while being read.");
                }
                attachment.setDataBase64(Base64.getEncoder().encodeToString(content));
                return Optional.of(attachment);
            } catch (IOException error) {
                throw new SQLException("Could not read attachment content.", error);
            }

        }
    }

    @Override
    public boolean setRead(int entryId, int accountId, boolean read) throws SQLException {
        return updateFlag("is_read", entryId, accountId, read);
    }

    @Override
    public boolean setStarred(int entryId, int accountId, boolean starred) throws SQLException {
        return updateFlag("is_starred", entryId, accountId, starred);
    }

    /**
     * Äáº·t má»™t cá» boolean trÃªn "báº£n thÆ° cá»§a tÃ´i". Tráº£ true náº¿u báº£n thÆ° thuá»™c tÃ i
     * khoáº£n (ká»ƒ cáº£ khi giÃ¡ trá»‹ khÃ´ng Ä‘á»•i, vÃ¬ MySQL bÃ¡o 0 dÃ²ng bá»‹ áº£nh hÆ°á»Ÿng).
     */
    private boolean updateFlag(String column, int entryId, int accountId, boolean value) throws SQLException {
        String sql = "UPDATE mail_recipient SET " + column + " = ? "
                + "WHERE entry_id = ? AND account_id = ? AND is_deleted = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, value);
            statement.setInt(2, entryId);
            statement.setInt(3, accountId);
            if (statement.executeUpdate() > 0) {
                return true;
            }
        }

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
        String updateSql = "UPDATE mail_recipient mr "
                + "JOIN folder trash ON trash.account_id = mr.account_id AND trash.folder_type = 'TRASH' "
                + "SET mr.original_folder_id = mr.folder_id, mr.folder_id = trash.folder_id "
                + "WHERE mr.entry_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE "
                + "AND mr.folder_id <> trash.folder_id";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
            if (statement.executeUpdate() > 0) {
                return true;
            }
        }

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
        List<Path> filesToDelete = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer mailId = findTrashMailId(connection, entryId, accountId);
                if (mailId == null) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM mail_recipient WHERE entry_id = ? AND account_id = ?")) {
                    statement.setInt(1, entryId);
                    statement.setInt(2, accountId);
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                deleteMailIfUnreferenced(connection, mailId, filesToDelete);
                connection.commit();
            } catch (SQLException error) {
                rollback(connection, error);
                throw error;
            }
        }
        deleteAttachmentFiles(filesToDelete);
        return true;
    }

    @Override
    public int emptyTrash(int accountId) throws SQLException {
        List<Path> filesToDelete = new ArrayList<>();
        Set<Integer> mailIds = new LinkedHashSet<>();
        int deletedEntries = 0;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
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
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM mail_recipient WHERE entry_id = ? AND account_id = ?")) {
                    for (int entryId : entryIds) {
                        statement.setInt(1, entryId);
                        statement.setInt(2, accountId);
                        statement.addBatch();
                    }
                    int[] results = statement.executeBatch();
                    for (int result : results) {
                        if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                            deletedEntries++;
                        }
                    }
                }
                for (int mailId : mailIds) {
                    deleteMailIfUnreferenced(connection, mailId, filesToDelete);
                }
                connection.commit();
            } catch (SQLException error) {
                rollback(connection, error);
                throw error;
            }
        }
        deleteAttachmentFiles(filesToDelete);
        return deletedEntries;
    }

    @Override
    public boolean restoreTrashMail(int entryId, int accountId) throws SQLException {
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

    private void deleteMailIfUnreferenced(Connection connection, int mailId, List<Path> filesToDelete)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT mail_id FROM mail WHERE mail_id = ? FOR UPDATE")) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM mail_recipient WHERE mail_id = ? LIMIT 1")) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT storage_path FROM attachment WHERE mail_id = ?")) {
            statement.setInt(1, mailId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    filesToDelete.add(AttachmentStorage.resolve(result.getString("storage_path")));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM mail WHERE mail_id = ?")) {
            statement.setInt(1, mailId);
            statement.executeUpdate();
        }
    }

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
        String sql = SUMMARY_SELECT;
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (m.subject LIKE ? ESCAPE '!' OR m.body LIKE ? ESCAPE '!')";
        }
        if (fromFilter != null && !fromFilter.isBlank()) {
            sql += " AND (sender.email LIKE ? ESCAPE '!' OR sender.display_name LIKE ? ESCAPE '!')";
        }
        if (folderId != null) {
            sql += " AND mr.folder_id = ?";
        }
        sql += SUMMARY_ORDER;
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
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
            try (ResultSet result = statement.executeQuery()) {
                List<MailSummary> summaries = new ArrayList<>();
                while (result.next()) {
                    summaries.add(readSummary(result));
                }
                return summaries;
            }
        }
    }

    private String searchPattern(String value) {
        String escaped = value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    @Override
    public Optional<StoredAttachment> findAccessibleAttachment(int attachmentId, int accountId) throws SQLException {
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
        detail.setTo(to);
        detail.setCc(cc);
        detail.setBcc(bcc);
    }

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

    private MailSummary readSummary(ResultSet result) throws SQLException {
        MailSummary summary = new MailSummary();
        summary.setMailId(result.getInt("mail_id"));
        summary.setEntryId(result.getInt("entry_id"));
        summary.setSenderEmail(result.getString("sender_email"));
        summary.setSenderName(result.getString("sender_name"));
        summary.setSubject(result.getString("subject"));
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
     * Äá»c DATETIME thÃ nh LocalDateTime nguyÃªn tráº¡ng (khÃ´ng quy Ä‘á»•i mÃºi giá»).
     * getTimestamp() + serverTimezone=UTC tá»«ng lÃ m giá» hiá»ƒn thá»‹ lá»‡ch +7 tiáº¿ng vÃ¬
     * MySQL lÆ°u giá» Ä‘á»‹a phÆ°Æ¡ng cÃ²n driver láº¡i coi Ä‘Ã³ lÃ  giá» UTC.
     */
    private String formatDateTime(java.time.LocalDateTime time) {
        return time == null ? null : time.format(ISO_DATE_TIME);
    }
}
