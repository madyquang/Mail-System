package com.mailsystem.server;

import com.mailsystem.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Cấu hình Server. Thứ tự ưu tiên cho mỗi khoá:
 * System property (-Dkey=...) &gt; biến môi trường &gt; db.properties &gt; mặc định.
 * Nhờ vậy mỗi thành viên có thể đặt mật khẩu MySQL riêng qua biến môi trường
 * mà không cần sửa file đã commit.
 */
public final class ServerConfig {

    private static final Properties PROPERTIES = load();

    private ServerConfig() {
    }

    public static int port() {
        return intValue("server.port", "MAIL_SERVER_PORT", ProtocolConstants.DEFAULT_PORT);
    }

    /** Số kết nối đồng thời tối đa; kết nối vượt mức bị từ chối lịch sự. */
    public static int maxClients() {
        return intValue("server.maxClients", "MAIL_SERVER_MAX_CLIENTS", 100);
    }

    /**
     * Thời gian tối đa không nhận được frame nào từ client trước khi Server đóng
     * kết nối (SO_TIMEOUT). Client gửi PING định kỳ nên chỉ kết nối "chết" mới
     * chạm ngưỡng này.
     */
    public static int idleTimeoutSeconds() {
        return intValue("server.idleTimeoutSeconds", "MAIL_SERVER_IDLE_TIMEOUT", 300);
    }

    public static Path attachmentsDir() {
        return Path.of(value("attachments.dir", "MAIL_ATTACHMENTS_DIR", "attachments"))
                .toAbsolutePath().normalize();
    }

    public static Path uploadTempDir() {
        return attachmentsDir().resolve("tmp");
    }

    public static String dbUrl() {
        return value("db.url", "MAIL_DB_URL",
                "jdbc:mysql://localhost:3306/mail_system?useSSL=false&serverTimezone=UTC");
    }

    public static String dbUsername() {
        return value("db.username", "MAIL_DB_USERNAME", "root");
    }

    public static String dbPassword() {
        return value("db.password", "MAIL_DB_PASSWORD", "");
    }

    private static String value(String key, String environmentName, String defaultValue) {
        String fromSystem = System.getProperty(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem.trim();
        }
        String fromEnvironment = System.getenv(environmentName);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        return PROPERTIES.getProperty(key, defaultValue).trim();
    }

    private static int intValue(String key, String environmentName, int defaultValue) {
        String raw = value(key, environmentName, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException error) {
            System.err.println("[ServerConfig] Gia tri khong hop le cho " + key + ": " + raw);
            return defaultValue;
        }
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) {
                properties.load(in);
            } else {
                System.err.println("[ServerConfig] Khong thay db.properties, dung gia tri mac dinh.");
            }
        } catch (IOException error) {
            System.err.println("[ServerConfig] Khong doc duoc db.properties: " + error.getMessage());
        }
        return properties;
    }
}
