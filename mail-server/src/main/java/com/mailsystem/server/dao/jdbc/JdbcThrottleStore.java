// Gói "dao.jdbc": các lớp DAO viết bằng JDBC thuần.
package com.mailsystem.server.dao.jdbc;

// DatabaseConnection: lấy kết nối từ pool.
import com.mailsystem.server.db.DatabaseConnection;
// ThrottleStore: interface mà lớp này thực hiện.
import com.mailsystem.server.security.ThrottleStore;

// Các lớp JDBC.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
// Instant (thời điểm UTC), LocalDateTime (ngày giờ không múi giờ), ZoneId (múi giờ).
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
// Optional: kết quả "có thể không có".
import java.util.Optional;

/**
 * Lưu bộ đếm đăng nhập sai vào bảng login_attempt. Lỗi CSDL không được làm
 * hỏng việc đăng nhập: chỉ ghi log và tiếp tục với bộ đếm trong bộ nhớ.
 */
public class JdbcThrottleStore implements ThrottleStore {

    // Múi giờ của máy chủ: cột DATETIME trong MySQL lưu giờ địa phương.
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Override
    public Optional<Record> load(String key) {
        // Đọc bộ đếm theo khoá.
        String sql = "SELECT failures, last_failure, locked_until FROM login_attempt WHERE attempt_key = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // ?1: khoá ("email:..." / "ip:...").
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                // Không có dòng nào: chưa từng sai.
                if (!result.next()) {
                    return Optional.empty();
                }
                // Đổi dòng kết quả thành Record (DATETIME -> Instant).
                return Optional.of(new Record(result.getInt("failures"),
                        toInstant(result.getObject("last_failure", LocalDateTime.class)),
                        toInstant(result.getObject("locked_until", LocalDateTime.class))));
            }
        } catch (SQLException error) {
            // Lỗi CSDL: ghi log và coi như chưa có bộ đếm.
            System.err.println("[ThrottleStore] Khong doc duoc login_attempt: " + error.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String key, Record record) {
        // Thêm mới, hoặc nếu khoá đã có (trùng khoá chính) thì cập nhật các cột.
        String sql = "INSERT INTO login_attempt (attempt_key, failures, last_failure, locked_until) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE failures = VALUES(failures), last_failure = VALUES(last_failure), "
                + "locked_until = VALUES(locked_until)";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // Gán 4 tham số theo thứ tự cột.
            statement.setString(1, key);
            statement.setInt(2, record.failures());
            statement.setObject(3, toLocal(record.lastFailure()));
            statement.setObject(4, toLocal(record.lockedUntil()));
            // Chạy câu lệnh ghi.
            statement.executeUpdate();
        } catch (SQLException error) {
            System.err.println("[ThrottleStore] Khong ghi duoc login_attempt: " + error.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        // Xoá bộ đếm của khoá.
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM login_attempt WHERE attempt_key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        } catch (SQLException error) {
            System.err.println("[ThrottleStore] Khong xoa duoc login_attempt: " + error.getMessage());
        }
    }

    // Đổi ngày giờ địa phương (đọc từ CSDL) thành Instant; null giữ null.
    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant();
    }

    // Đổi Instant thành ngày giờ địa phương (để ghi vào CSDL); null giữ null.
    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }
}
