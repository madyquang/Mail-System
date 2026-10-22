package com.mailsystem.server.db;

import com.mailsystem.server.config.ServerConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Cấp kết nối MySQL từ một connection pool (HikariCP).
 *
 * Mở một kết nối MySQL mới tốn vài chục mili giây (bắt tay TCP, bắt tay TLS,
 * xác thực). Pool giữ sẵn tối đa db.poolSize kết nối và cho mượn: close() trên
 * Connection lấy từ pool chỉ trả kết nối về pool, không đóng thật. Vì vậy mọi
 * DAO vẫn dùng try-with-resources như cũ.
 */
public final class DatabaseConnection {

    private static volatile HikariDataSource dataSource;

    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    private static HikariDataSource pool() {
        HikariDataSource current = dataSource;
        if (current == null) {
            synchronized (DatabaseConnection.class) {
                current = dataSource;
                if (current == null) {
                    HikariConfig config = new HikariConfig();
                    config.setPoolName("mail-db");
                    config.setJdbcUrl(ServerConfig.dbUrl());
                    config.setUsername(ServerConfig.dbUsername());
                    config.setPassword(ServerConfig.dbPassword());
                    config.setMaximumPoolSize(ServerConfig.dbPoolSize());
                    config.setMinimumIdle(Math.min(2, ServerConfig.dbPoolSize()));
                    // Không để thread xử lý client chờ vô hạn khi MySQL quá tải hoặc tắt.
                    config.setConnectionTimeout(5_000);
                    config.setValidationTimeout(3_000);
                    // Khởi động Server được kể cả khi MySQL chưa chạy (lỗi báo ở lần dùng đầu).
                    config.setInitializationFailTimeout(-1);
                    current = new HikariDataSource(config);
                    dataSource = current;
                }
            }
        }
        return current;
    }

    /** Số liệu pool cho bảng giám sát: đang dùng / rảnh / tổng / thread đang chờ. */
    public record PoolStats(int active, int idle, int total, int waiting, int max) {
    }

    public static PoolStats poolStats() {
        HikariDataSource current = dataSource;
        if (current == null) {
            return new PoolStats(0, 0, 0, 0, ServerConfig.dbPoolSize());
        }
        HikariPoolMXBean bean = current.getHikariPoolMXBean();
        if (bean == null) {
            return new PoolStats(0, 0, 0, 0, current.getMaximumPoolSize());
        }
        return new PoolStats(bean.getActiveConnections(), bean.getIdleConnections(), bean.getTotalConnections(),
                bean.getThreadsAwaitingConnection(), current.getMaximumPoolSize());
    }

    /** Bộ mã hoá TLS của kết nối tới MySQL, hoặc null nếu kết nối không mã hoá. */
    public static String tlsCipher() {
        try (Connection connection = getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            if (result.next()) {
                String cipher = result.getString(2);
                return cipher == null || cipher.isBlank() ? null : cipher;
            }
            return null;
        } catch (SQLException error) {
            return null;
        }
    }

    public static void shutdown() {
        HikariDataSource current = dataSource;
        if (current != null) {
            current.close();
        }
    }
}
