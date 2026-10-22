package com.mailsystem.server.config;

import com.mailsystem.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Cấu hình Server, đọc theo nhiều lớp (lớp sau ghi đè lớp trước):
 * <ol>
 *   <li>server-defaults.properties trong classpath (giá trị mặc định, được commit, KHÔNG chứa bí mật)</li>
 *   <li>config/server.properties trong thư mục dự án (bí mật của máy, bị .gitignore)</li>
 *   <li>biến môi trường (VD: MAIL_DB_PASSWORD)</li>
 *   <li>System property -Dkey=value</li>
 * </ol>
 * Đường dẫn tương đối được tính từ thư mục gốc dự án (homeDir), nên chạy từ
 * IDE hay từ terminal ở thư mục nào cũng dùng chung một thư mục data/.
 */
public final class ServerConfig {

    private static final Path HOME = detectHome();
    private static final Properties PROPERTIES = load();

    private ServerConfig() {
    }

    // ------------------------------------------------------------------ mạng

    public static int port() {
        return intValue("server.port", "MAIL_SERVER_PORT", ProtocolConstants.DEFAULT_PORT);
    }

    /** Số kết nối đồng thời tối đa; kết nối vượt mức bị từ chối lịch sự. */
    public static int maxClients() {
        return intValue("server.maxClients", "MAIL_SERVER_MAX_CLIENTS", 100);
    }

    /** Đóng kết nối không gửi frame nào trong khoảng này (SO_TIMEOUT). */
    public static int idleTimeoutSeconds() {
        return intValue("server.idleTimeoutSeconds", "MAIL_SERVER_IDLE_TIMEOUT", 300);
    }

    // ------------------------------------------------------------------ lưu trữ

    public static Path homeDir() {
        return HOME;
    }

    public static Path dataDir() {
        return path("data.dir", "MAIL_DATA_DIR", "data");
    }

    public static Path attachmentsDir() {
        return path("attachments.dir", "MAIL_ATTACHMENTS_DIR", dataDir().resolve("attachments").toString());
    }

    public static Path uploadTempDir() {
        return attachmentsDir().resolve("tmp");
    }

    public static Path logsDir() {
        return path("logs.dir", "MAIL_LOGS_DIR", dataDir().resolve("logs").toString());
    }

    // ------------------------------------------------------------------ TLS

    public static Path tlsKeystore() {
        return path("tls.keystore", "MAIL_TLS_KEYSTORE", dataDir().resolve("tls").resolve("server.p12").toString());
    }

    /** Rỗng: Server tự sinh mật khẩu ngẫu nhiên và lưu cạnh keystore. */
    public static String tlsKeystorePassword() {
        return value("tls.keystorePassword", "MAIL_TLS_KEYSTORE_PASSWORD", "");
    }

    // ------------------------------------------------------------------ bảo mật

    public static int loginMaxFailures() {
        return intValue("security.loginMaxFailures", "MAIL_LOGIN_MAX_FAILURES", 5);
    }

    public static int loginLockMinutes() {
        return intValue("security.loginLockMinutes", "MAIL_LOGIN_LOCK_MINUTES", 15);
    }

    public static int requestsPerSecond() {
        return intValue("security.requestsPerSecond", "MAIL_REQUESTS_PER_SECOND", 30);
    }

    public static int requestBurst() {
        return intValue("security.requestBurst", "MAIL_REQUEST_BURST", 120);
    }

    public static int registrationsPerHourPerIp() {
        return intValue("security.registrationsPerHourPerIp", "MAIL_REGISTRATIONS_PER_HOUR", 10);
    }

    // ------------------------------------------------------------------ MySQL

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

    // ------------------------------------------------------------------ nội bộ

    private static Path path(String key, String environmentName, String defaultValue) {
        Path raw = Path.of(value(key, environmentName, defaultValue));
        return (raw.isAbsolute() ? raw : HOME.resolve(raw)).normalize();
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

    /**
     * Thư mục gốc dự án: MAIL_HOME nếu có; nếu đang đứng trong mail-server/
     * (IDE đặt working directory là module) thì lùi lên một cấp.
     */
    private static Path detectHome() {
        String configured = System.getProperty("mail.home", System.getenv("MAIL_HOME"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        if (current.getFileName() != null && current.getFileName().toString().equals("mail-server")
                && Files.isDirectory(current.resolveSibling("mail-common"))) {
            return current.getParent();
        }
        return current;
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream("server-defaults.properties")) {
            if (in != null) {
                properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            System.err.println("[ServerConfig] Khong doc duoc server-defaults.properties: " + error.getMessage());
        }
        String externalPath = System.getProperty("mail.config", System.getenv("MAIL_CONFIG"));
        Path external = externalPath == null || externalPath.isBlank()
                ? HOME.resolve("config").resolve("server.properties")
                : Path.of(externalPath);
        if (Files.isRegularFile(external)) {
            try (Reader reader = Files.newBufferedReader(external, StandardCharsets.UTF_8)) {
                properties.load(reader);
                System.out.println("[ServerConfig] Da nap cau hinh: " + external);
            } catch (IOException error) {
                System.err.println("[ServerConfig] Khong doc duoc " + external + ": " + error.getMessage());
            }
        }
        return properties;
    }
}
