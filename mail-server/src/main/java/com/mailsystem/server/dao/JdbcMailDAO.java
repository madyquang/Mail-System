package com.mailsystem.server.dao;

import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.server.db.DatabaseConnection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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
        String sql = "INSERT INTO mail (sender_id, subject, body) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, senderId);
            statement.setString(2, subject);
            statement.setString(3, body);
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

    @Override
    public List<MailSummary> getMailList(int accountId, int folderId) throws SQLException {
        String sql = "SELECT m.mail_id, mr.entry_id, sender.email AS sender_email, "
                + "sender.display_name AS sender_name, m.subject, m.sent_at, mr.is_read "
                + "FROM mail_recipient mr "
                + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
                + "JOIN mail m ON m.mail_id = mr.mail_id "
                + "JOIN account sender ON sender.account_id = m.sender_id "
                + "WHERE mr.account_id = ? AND mr.folder_id = ? AND mr.is_deleted = FALSE "
                + "ORDER BY m.sent_at DESC, mr.entry_id DESC";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, folderId);
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
    public Optional<MailDetail> getMailDetail(int mailId, int accountId) throws SQLException {
        String sql = "SELECT mr.entry_id, mr.recipient_type, m.sender_id, sender.email AS sender_email, "
                + "sender.display_name AS sender_name, m.subject, m.body, m.sent_at, f.folder_type "
                + "FROM mail_recipient mr JOIN mail m ON m.mail_id = mr.mail_id "
                + "JOIN account sender ON sender.account_id = m.sender_id "
                + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
                + "WHERE mr.mail_id = ? AND mr.account_id = ? AND mr.is_deleted = FALSE LIMIT 1";
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MailDetail detail;
                int entryId;
                String recipientType;
                String folderType;
                int senderId;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, mailId);
                    statement.setInt(2, accountId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        entryId = result.getInt("entry_id");
                        recipientType = result.getString("recipient_type");
                        folderType = result.getString("folder_type");
                        senderId = result.getInt("sender_id");
                        detail = new MailDetail();
                        detail.setMailId(mailId);
                        detail.setSenderEmail(result.getString("sender_email"));
                        detail.setSenderName(result.getString("sender_name"));
                        detail.setSubject(result.getString("subject"));
                        detail.setBody(result.getString("body"));
                        detail.setSentAt(formatDateTime(result.getTimestamp("sent_at")));
                    }
                }

                boolean isSender = accountId == senderId || "SENDER".equals(recipientType);
                boolean isBccRecipient = "BCC".equals(recipientType);
                loadRecipients(connection, detail, mailId, accountId, isSender, isBccRecipient);
                detail.setAttachments(loadAttachments(connection, mailId));
                if (!"TRASH".equalsIgnoreCase(folderType)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE mail_recipient SET is_read = TRUE WHERE entry_id = ? AND account_id = ?")) {
                        statement.setInt(1, entryId);
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

                Path storedPath = Path.of(result.getString("storage_path"));
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
    public boolean markRead(int entryId, int accountId) throws SQLException {
        String sql = "UPDATE mail_recipient SET is_read = TRUE "
                + "WHERE entry_id = ? AND account_id = ? AND is_deleted = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, entryId);
            statement.setInt(2, accountId);
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
                    filesToDelete.add(Path.of(result.getString("storage_path")));
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
                System.err.println("[JdbcMailDAO] Không xóa được tệp đính kèm orphan "
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
        String sql = "SELECT m.mail_id, mr.entry_id, sender.email AS sender_email, "
                + "sender.display_name AS sender_name, m.subject, m.sent_at, mr.is_read "
                + "FROM mail_recipient mr "
                + "JOIN folder f ON f.folder_id = mr.folder_id AND f.account_id = mr.account_id "
                + "JOIN mail m ON m.mail_id = mr.mail_id "
                + "JOIN account sender ON sender.account_id = m.sender_id "
                + "WHERE mr.account_id = ? AND mr.is_deleted = FALSE";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (m.subject LIKE ? ESCAPE '!' OR m.body LIKE ? ESCAPE '!')";
        }
        if (fromFilter != null && !fromFilter.isBlank()) {
            sql += " AND (sender.email LIKE ? ESCAPE '!' OR sender.display_name LIKE ? ESCAPE '!')";
        }
        if (folderId != null) {
            sql += " AND mr.folder_id = ?";
        }
        sql += " ORDER BY m.sent_at DESC, mr.entry_id DESC";
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
        summary.setSentAt(formatDateTime(result.getTimestamp("sent_at")));
        summary.setRead(result.getBoolean("is_read"));
        return summary;
    }

    private String formatDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().format(ISO_DATE_TIME);
    }
}