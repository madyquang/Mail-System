// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// JSON (đọc payload), DTO thư chờ, Gson dùng chung.
import com.google.gson.JsonObject;
import com.mailsystem.common.model.ScheduledMail;
import com.mailsystem.common.protocol.ProtocolUtil;
// Interface mà lớp này thực hiện; kết nối CSDL.
import com.mailsystem.server.dao.OutboxDAO;
import com.mailsystem.server.db.DatabaseConnection;

// Các lớp JDBC.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
// Ngày giờ và định dạng ISO-8601.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
// Danh sách, Optional.
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Truy cập bảng outbox / outbox_attachment (thư hẹn giờ) bằng JDBC. */
public class JdbcOutboxDAO implements OutboxDAO {

    @Override
    public int insert(int accountId, String payload, LocalDateTime sendAt, List<StoredFile> files) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            // Ghi outbox + các tệp trong một transaction.
            connection.setAutoCommit(false);
            try {
                int outboxId;
                // Bước 1: thêm dòng outbox, lấy mã tự tăng.
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
                // Bước 2: thêm các tệp đính kèm (nếu có).
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
                            // Gom vào lô để gửi MySQL một lần.
                            statement.addBatch();
                        }
                        // Chạy cả lô.
                        statement.executeBatch();
                    }
                }
                connection.commit();
                return outboxId;
            } catch (SQLException error) {
                // Lỗi: huỷ cả transaction.
                connection.rollback();
                throw error;
            }
        }
    }

    @Override
    public List<ScheduledMail> listByAccount(int accountId) throws SQLException {
        // Thư chờ của tài khoản, kèm số tệp (truy vấn con), gần giờ gửi nhất xếp trước.
        String sql = "SELECT o.outbox_id, o.payload, o.send_at, "
                + "(SELECT COUNT(*) FROM outbox_attachment a WHERE a.outbox_id = o.outbox_id) AS files "
                + "FROM outbox o WHERE o.account_id = ? ORDER BY o.send_at";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                List<ScheduledMail> mails = new ArrayList<>();
                while (result.next()) {
                    // Đọc payload JSON để lấy người nhận và tiêu đề.
                    JsonObject payload = ProtocolUtil.fromJson(result.getString("payload"), JsonObject.class);
                    // Mảng "to" ghép thành chuỗi "a, b, c".
                    String to = payload.has("to")
                            ? String.join(", ", ProtocolUtil.getGson().fromJson(payload.get("to"), String[].class))
                            : "";
                    String subject = payload.has("subject") ? payload.get("subject").getAsString() : "";
                    // Thêm một thư chờ (giờ gửi định dạng ISO-8601).
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
        // Thư có giờ gửi <= bây giờ (giờ của MySQL), cũ nhất trước.
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
        // Bước 1: đọc và KHOÁ dòng (FOR UPDATE): transaction khác muốn khoá cùng dòng phải chờ.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT outbox_id, account_id, payload, send_at FROM outbox WHERE outbox_id = ? FOR UPDATE")) {
            statement.setInt(1, outboxId);
            try (ResultSet result = statement.executeQuery()) {
                // Không còn dòng (đã huỷ / đã gửi), hoặc không thuộc tài khoản yêu cầu.
                if (!result.next() || (accountId != null && result.getInt("account_id") != accountId)) {
                    return Optional.empty();
                }
                entry = new Entry(outboxId, result.getInt("account_id"), result.getString("payload"),
                        result.getObject("send_at", LocalDateTime.class), new ArrayList<>());
            }
        }
        // Bước 2: đọc các tệp đính kèm theo thứ tự đã thêm.
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
        // Bước 3: xoá dòng outbox (outbox_attachment bị xoá theo nhờ ON DELETE CASCADE).
        // Việc xoá chỉ có hiệu lực khi nơi gọi commit; rollback thì dòng quay lại.
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM outbox WHERE outbox_id = ?")) {
            statement.setInt(1, outboxId);
            statement.executeUpdate();
        }
        return Optional.of(entry);
    }
}
