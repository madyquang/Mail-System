// Gói "config": đọc cấu hình Server.
package com.mailsystem.server.config;

// Hằng số giao thức (cổng mặc định).
import com.mailsystem.common.protocol.ProtocolConstants;

// Vào/ra, bảng mã, đường dẫn, Properties (tệp key=value).
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
 *
 * Mỗi hàm bên dưới đọc một khoá: tên khoá trong tệp, tên biến môi trường, giá trị mặc định.
 */
public final class ServerConfig {

    // Thư mục gốc dự án (xác định một lần khi nạp lớp).
    private static final Path HOME = detectHome();
    // Các giá trị đọc từ tệp (lớp 1 + 2).
    private static final Properties PROPERTIES = load();

    // Constructor private: lớp chỉ có hàm tĩnh.
    private ServerConfig() {
    }

    // ------------------------------------------------------------------ mạng

    /** Cổng TCP của Server (mặc định 5000). */
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

    /**
     * Mô hình thread cho kết nối: "virtual" (mặc định, rẻ, phục vụ hàng nghìn
     * kết nối), "platform" (thread hệ điều hành), "auto" = virtual thread.
     */
    public static String threadMode() {
        return value("server.threads", "MAIL_SERVER_THREADS", "auto");
    }

    /** Số kết nối MySQL tối đa trong pool. */
    public static int dbPoolSize() {
        return intValue("db.poolSize", "MAIL_DB_POOL_SIZE", 10);
    }

    /** Bảng giám sát HTTP (chỉ nghe trên 127.0.0.1). */
    public static boolean monitorEnabled() {
        // Bật trừ khi cấu hình đúng chữ "false".
        return !"false".equalsIgnoreCase(value("monitor.enabled", "MAIL_MONITOR_ENABLED", "true"));
    }

    /** Cổng của bảng giám sát (mặc định 8090). */
    public static int monitorPort() {
        return intValue("monitor.port", "MAIL_MONITOR_PORT", 8090);
    }

    /** Mã truy cập bảng giám sát; rỗng = sinh ngẫu nhiên mỗi lần khởi động. */
    public static String monitorToken() {
        return value("monitor.token", "MAIL_MONITOR_TOKEN", "");
    }

    /**
     * Xác thực thiết bị bằng chứng chỉ client (mutual TLS):
     * "off" = không hỏi; "want" = ghi nhận thiết bị nếu có (mặc định);
     * "need" = chỉ thiết bị đã được phê duyệt trên bảng giám sát mới kết nối được.
     */
    public static String tlsClientAuth() {
        return value("tls.clientAuth", "MAIL_TLS_CLIENT_AUTH", "want").toLowerCase(java.util.Locale.ROOT);
    }

    /** Thời hạn token tiếp tục phiên sau khi rớt mạng (phút). */
    public static int resumeTokenMinutes() {
        return intValue("security.resumeTokenMinutes", "MAIL_RESUME_TOKEN_MINUTES", 720);
    }

    /** Tệp tải lên dở dang được giữ để tải tiếp sau khi rớt mạng (phút). */
    public static int uploadExpiryMinutes() {
        return intValue("upload.expiryMinutes", "MAIL_UPLOAD_EXPIRY_MINUTES", 30);
    }

    // ------------------------------------------------------------------ lưu trữ

    /** @return thư mục gốc dự án */
    public static Path homeDir() {
        return HOME;
    }

    /** Thư mục dữ liệu (mặc định "data" trong thư mục gốc). */
    public static Path dataDir() {
        return path("data.dir", "MAIL_DATA_DIR", "data");
    }

    /** Thư mục lưu tệp đính kèm (mặc định data/attachments). */
    public static Path attachmentsDir() {
        return path("attachments.dir", "MAIL_ATTACHMENTS_DIR", dataDir().resolve("attachments").toString());
    }

    /** Thư mục tệp tạm khi đang tải lên (data/attachments/tmp). */
    public static Path uploadTempDir() {
        return attachmentsDir().resolve("tmp");
    }

    /** Thư mục nhật ký (mặc định data/logs). */
    public static Path logsDir() {
        return path("logs.dir", "MAIL_LOGS_DIR", dataDir().resolve("logs").toString());
    }

    // ------------------------------------------------------------------ TLS

    /** Tệp keystore chứa khoá + chứng chỉ TLS (mặc định data/tls/server.p12). */
    public static Path tlsKeystore() {
        return path("tls.keystore", "MAIL_TLS_KEYSTORE", dataDir().resolve("tls").resolve("server.p12").toString());
    }

    /** Rỗng: Server tự sinh mật khẩu ngẫu nhiên và lưu cạnh keystore. */
    public static String tlsKeystorePassword() {
        return value("tls.keystorePassword", "MAIL_TLS_KEYSTORE_PASSWORD", "");
    }

    // ------------------------------------------------------------------ bảo mật

    /** Số lần đăng nhập sai theo email trước khi bị khoá (mặc định 5; theo IP gấp 4). */
    public static int loginMaxFailures() {
        return intValue("security.loginMaxFailures", "MAIL_LOGIN_MAX_FAILURES", 5);
    }

    /** Thời gian khoá đăng nhập (phút, mặc định 15). */
    public static int loginLockMinutes() {
        return intValue("security.loginLockMinutes", "MAIL_LOGIN_LOCK_MINUTES", 15);
    }

    /** Số request trung bình mỗi giây cho một kết nối (mặc định 30). */
    public static int requestsPerSecond() {
        return intValue("security.requestsPerSecond", "MAIL_REQUESTS_PER_SECOND", 30);
    }

    /** Số request được dồn một lúc (mặc định 120). */
    public static int requestBurst() {
        return intValue("security.requestBurst", "MAIL_REQUEST_BURST", 120);
    }

    /** Số lần đăng ký tài khoản tối đa mỗi IP mỗi giờ (mặc định 10). */
    public static int registrationsPerHourPerIp() {
        return intValue("security.registrationsPerHourPerIp", "MAIL_REGISTRATIONS_PER_HOUR", 10);
    }

    // ------------------------------------------------------------------ MySQL

    /** Địa chỉ JDBC của MySQL (sslMode=PREFERRED: dùng TLS nếu MySQL hỗ trợ). */
    public static String dbUrl() {
        return value("db.url", "MAIL_DB_URL",
                "jdbc:mysql://localhost:3306/mail_system?sslMode=PREFERRED");
    }

    /** Tài khoản MySQL. */
    public static String dbUsername() {
        return value("db.username", "MAIL_DB_USERNAME", "root");
    }

    /** Mật khẩu MySQL (chỉ đặt trong config/server.properties hoặc biến môi trường, không commit). */
    public static String dbPassword() {
        return value("db.password", "MAIL_DB_PASSWORD", "");
    }

    // ------------------------------------------------------------------ nội bộ

    // Đọc một giá trị đường dẫn; đường dẫn tương đối thì tính từ thư mục gốc.
    private static Path path(String key, String environmentName, String defaultValue) {
        Path raw = Path.of(value(key, environmentName, defaultValue));
        return (raw.isAbsolute() ? raw : HOME.resolve(raw)).normalize();
    }

    // Đọc một giá trị theo thứ tự ưu tiên: System property > biến môi trường > tệp > mặc định.
    private static String value(String key, String environmentName, String defaultValue) {
        // 1. -Dkey=value khi chạy java.
        String fromSystem = System.getProperty(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem.trim();
        }
        // 2. Biến môi trường.
        String fromEnvironment = System.getenv(environmentName);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        // 3. Tệp cấu hình (đã gộp mặc định + config/server.properties); không có thì mặc định.
        return PROPERTIES.getProperty(key, defaultValue).trim();
    }

    // Đọc số nguyên dương; sai định dạng hoặc <= 0 thì dùng mặc định.
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
        // Ưu tiên -Dmail.home, rồi biến môi trường MAIL_HOME.
        String configured = System.getProperty("mail.home", System.getenv("MAIL_HOME"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        // Thư mục làm việc hiện tại.
        Path current = Path.of("").toAbsolutePath().normalize();
        // Đang ở mail-server/ và bên cạnh có mail-common/ thì gốc là thư mục cha.
        if (current.getFileName() != null && current.getFileName().toString().equals("mail-server")
                && Files.isDirectory(current.resolveSibling("mail-common"))) {
            return current.getParent();
        }
        return current;
    }

    // Nạp tệp cấu hình: mặc định trong jar rồi ghi đè bằng config/server.properties.
    private static Properties load() {
        Properties properties = new Properties();
        // Lớp 1: server-defaults.properties trong classpath.
        try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream("server-defaults.properties")) {
            if (in != null) {
                properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            System.err.println("[ServerConfig] Khong doc duoc server-defaults.properties: " + error.getMessage());
        }
        // Lớp 2: tệp ngoài (-Dmail.config / MAIL_CONFIG, mặc định config/server.properties).
        String externalPath = System.getProperty("mail.config", System.getenv("MAIL_CONFIG"));
        Path external = externalPath == null || externalPath.isBlank()
                ? HOME.resolve("config").resolve("server.properties")
                : Path.of(externalPath);
        if (Files.isRegularFile(external)) {
            try (Reader reader = Files.newBufferedReader(external, StandardCharsets.UTF_8)) {
                // load() lần hai ghi đè các khoá trùng.
                properties.load(reader);
                System.out.println("[ServerConfig] Da nap cau hinh: " + external);
            } catch (IOException error) {
                System.err.println("[ServerConfig] Khong doc duoc " + external + ": " + error.getMessage());
            }
        }
        return properties;
    }
}
