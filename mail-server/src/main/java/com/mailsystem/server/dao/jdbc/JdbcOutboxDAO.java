package com.mailsystem.server.dao.jdbc;

import com.google.gson.JsonObject;
import com.mailsystem.common.model.ScheduledMail;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.server.dao.OutboxDAO;
import com.mailsystem.server.db.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcOutboxDAO implements OutboxDAO {

    @Override
    public int insert(int accountId, String payload, LocalDateTime sendAt, List<StoredFile> files) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int outboxId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO outbox (account_id, payload, send_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setInt(1, accountId);
                    statement.setString(2, payload);
                    statement.setObject(3, sendAt);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        outboxId = keys.getInt(1);
                    }
                }
                if (!files.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO outbox_attachment (outbox_id, filename, mime_type, size_bytes, storage_path) "
                                    + "VALUES (?, ?, ?, ?, ?)")) {
                        for (StoredFile file : files) {
                            statement.setInt(1, outboxId);
                            statement.setString(2, file.filename());
                            statement.setString(3, file.mimeType());
                            statement.setLong(4, file.sizeBytes());
                            statement.setString(5, file.storagePath());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                connection.commit();
                return outboxId;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    @Override
    public List<ScheduledMail> listByAccount(int accountId) throws SQLException {
        String sql = "SELECT o.outbox_id, o.payload, o.send_at, "
                + "(SELECT COUNT(*) FROM outbox_attachment a WHERE a.outbox_id = o.outbox_id) AS files "
                + "FROM outbox o WHERE o.account_id = ? ORDER BY o.send_at";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                List<ScheduledMail> mails = new ArrayList<>();
                while (result.next()) {
                    JsonObject payload = ProtocolUtil.fromJson(result.getString("payload"), JsonObject.class);
                    String to = payload.has("to")
                            ? String.join(", ", ProtocolUtil.getGson().fromJson(payload.get("to"), String[].class))
                            : "";
                    String subject = payload.has("subject") ? payload.get("subject").getAsString() : "";
                    mails.add(new ScheduledMail(result.getInt("outbox_id"), to, subject,
                            result.getObject("send_at", LocalDateTime.class).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            result.getInt("files")));
                }
                return mails;
            }
        }
    }

    @Override
    public List<Integer> findDueIds(int limit) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT outbox_id FROM outbox WHERE send_at <= NOW() ORDER BY send_at LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Integer> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getInt(1));
                }
                return ids;
            }
        }
    }

    @Override
    public Optional<Entry> claim(Connection connection, int outboxId, Integer accountId) throws SQLException {
        Entry entry;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT outbox_id, account_id, payload, send_at FROM outbox WHERE outbox_id = ? FOR UPDATE")) {
            statement.setInt(1, outboxId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || (accountId != null && result.getInt("account_id") != accountId)) {
                    return Optional.empty();
                }
                entry = new Entry(outboxId, result.getInt("account_id"), result.getString("payload"),
                        result.getObject("send_at", LocalDateTime.class), new ArrayList<>());
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT filename, mime_type, size_bytes, storage_path FROM outbox_attachment WHERE outbox_id = ? "
                        + "ORDER BY outbox_attachment_id")) {
            statement.setInt(1, outboxId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entry.files().add(new StoredFile(result.getString("filename"), result.getString("mime_type"),
                            result.getLong("size_bytes"), result.getString("storage_path")));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM outbox WHERE outbox_id = ?")) {
            statement.setInt(1, outboxId);
            statement.executeUpdate();
        }
        return Optional.of(entry);
    }
}
