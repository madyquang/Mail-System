package com.mailsystem.server.db;

import com.mailsystem.server.ServerConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Quản lý kết nối JDBC tới MySQL. Mỗi lần gọi mở 1 connection mới (đơn giản,
 * đủ cho đồ án); có thể nâng cấp thành connection pool (VD: HikariCP).
 */
public final class DatabaseConnection {

    static {
        // Không để thread xử lý client bị treo vô hạn khi MySQL không phản hồi.
        DriverManager.setLoginTimeout(5);
    }

    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                ServerConfig.dbUrl(), ServerConfig.dbUsername(), ServerConfig.dbPassword());
    }
}
