package com.mailsystem.server.db;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Quản lý kết nối JDBC tới MySQL.
 * TODO (TV2): day la ban don gian (mo 1 connection moi cho moi request).
 * Neu con thoi gian, nang cap thanh Connection Pool (VD: HikariCP) o Buoc nang cao.
 */
public final class DatabaseConnection {

    private static Properties props;

    static {
        props = new Properties();
        try (InputStream in = DatabaseConnection.class.getClassLoader()
                .getResourceAsStream("db.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Khong doc duoc db.properties", e);
        }
    }

    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        String url = props.getProperty("db.url");
        String user = props.getProperty("db.username");
        String pass = props.getProperty("db.password");
        return DriverManager.getConnection(url, user, pass);
    }
}
