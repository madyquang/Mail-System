package com.mailsystem.server.dao.jdbc;

import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.security.ThrottleStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Lưu bộ đếm đăng nhập sai vào bảng login_attempt. Lỗi CSDL không được làm
 * hỏng việc đăng nhập: chỉ ghi log và tiếp tục với bộ đếm trong bộ nhớ.
 */
public class JdbcThrottleStore implements ThrottleStore {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Override
    public Optional<Record> load(String key) {
        String sql = "SELECT failures, last_failure, locked_until FROM login_attempt WHERE attempt_key = ?";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Record(result.getInt("failures"),
                        toInstant(result.getObject("last_failure", LocalDateTime.class)),
                        toInstant(result.getObject("locked_until", LocalDateTime.class))));
            }
        } catch (SQLException error) {
            System.err.println("[ThrottleStore] Khong doc duoc login_attempt: " + error.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String key, Record record) {
        String sql = "INSERT INTO login_attempt (attempt_key, failures, last_failure, locked_until) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE failures = VALUES(failures), last_failure = VALUES(last_failure), "
                + "locked_until = VALUES(locked_until)";
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setInt(2, record.failures());
            statement.setObject(3, toLocal(record.lastFailure()));
            statement.setObject(4, toLocal(record.lockedUntil()));
            statement.executeUpdate();
        } catch (SQLException error) {
            System.err.println("[ThrottleStore] Khong ghi duoc login_attempt: " + error.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        try (Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM login_attempt WHERE attempt_key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        } catch (SQLException error) {
            System.err.println("[ThrottleStore] Khong xoa duoc login_attempt: " + error.getMessage());
        }
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant();
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }
}
