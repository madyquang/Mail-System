// Gói "db": kết nối tới MySQL.
package com.mailsystem.server.db;

// ServerConfig: đọc thông số kết nối CSDL từ cấu hình.
import com.mailsystem.server.config.ServerConfig;
// HikariCP: thư viện connection pool.
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
// HikariPoolMXBean: đọc số liệu của pool (đang dùng, rảnh...).
import com.zaxxer.hikari.HikariPoolMXBean;

// Các lớp JDBC.
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

    // Pool duy nhất, tạo lần đầu khi cần (volatile cho kiểu khởi tạo "double-checked locking").
    private static volatile HikariDataSource dataSource;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private DatabaseConnection() {
    }

    /**
     * Mượn một kết nối từ pool. Nơi gọi phải close() (thường bằng try-with-resources) để trả lại.
     *
     * @return kết nối MySQL
     */
    public static Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    // Lấy pool, tạo nếu chưa có (chỉ một thread được tạo).
    private static HikariDataSource pool() {
        // Đọc một lần (nhanh, không khoá) cho trường hợp phổ biến: pool đã có.
        HikariDataSource current = dataSource;
        if (current == null) {
            // Chưa có: khoá lớp để chỉ một thread tạo pool.
            synchronized (DatabaseConnection.class) {
                // Đọc lại trong khoá: có thể thread khác vừa tạo xong.
                current = dataSource;
                if (current == null) {
                    // Cấu hình pool.
                    HikariConfig config = new HikariConfig();
                    // Tên pool (hiện trong log).
                    config.setPoolName("mail-db");
                    // Địa chỉ, tài khoản, mật khẩu MySQL từ cấu hình.
                    config.setJdbcUrl(ServerConfig.dbUrl());
                    config.setUsername(ServerConfig.dbUsername());
                    config.setPassword(ServerConfig.dbPassword());
                    // Số kết nối tối đa.
                    config.setMaximumPoolSize(ServerConfig.dbPoolSize());
                    // Giữ sẵn tối thiểu 2 kết nối rảnh (không quá kích thước pool).
                    config.setMinimumIdle(Math.min(2, ServerConfig.dbPoolSize()));
                    // Không để thread xử lý client chờ vô hạn khi MySQL quá tải hoặc tắt.
                    config.setConnectionTimeout(5_000);
                    config.setValidationTimeout(3_000);
                    // Khởi động Server được kể cả khi MySQL chưa chạy (lỗi báo ở lần dùng đầu).
                    config.setInitializationFailTimeout(-1);
                    // Tạo pool rồi lưu lại.
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

    /** @return số liệu hiện tại của pool (toàn 0 nếu pool chưa được tạo) */
    public static PoolStats poolStats() {
        HikariDataSource current = dataSource;
        // Pool chưa tạo.
        if (current == null) {
            return new PoolStats(0, 0, 0, 0, ServerConfig.dbPoolSize());
        }
        // Bean số liệu có thể null khi pool đang khởi tạo.
        HikariPoolMXBean bean = current.getHikariPoolMXBean();
        if (bean == null) {
            return new PoolStats(0, 0, 0, 0, current.getMaximumPoolSize());
        }
        // Đọc số kết nối đang dùng, rảnh, tổng, số thread đang chờ, và kích thước tối đa.
        return new PoolStats(bean.getActiveConnections(), bean.getIdleConnections(), bean.getTotalConnections(),
                bean.getThreadsAwaitingConnection(), current.getMaximumPoolSize());
    }

    /** Bộ mã hoá TLS của kết nối tới MySQL, hoặc null nếu kết nối không mã hoá. */
    public static String tlsCipher() {
        // Hỏi MySQL biến trạng thái Ssl_cipher của phiên hiện tại.
        try (Connection connection = getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            if (result.next()) {
                // Cột 2 là giá trị; rỗng nghĩa là không dùng TLS.
                String cipher = result.getString(2);
                return cipher == null || cipher.isBlank() ? null : cipher;
            }
            return null;
        } catch (SQLException error) {
            // Không hỏi được (vd MySQL tắt): coi như không biết.
            return null;
        }
    }

    /** Đóng pool (gọi khi tắt Server). */
    public static void shutdown() {
        HikariDataSource current = dataSource;
        if (current != null) {
            current.close();
        }
    }
}
