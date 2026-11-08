// Gói gốc của Server: chứa lớp có hàm main.
package com.mailsystem.server;

// Cấu hình, CSDL, trang giám sát, mạng, bảo mật, service, lưu tệp, tải lên.
import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.monitor.DashboardServer;
import com.mailsystem.server.net.CommandRouter;
import com.mailsystem.server.net.MailServer;
import com.mailsystem.server.security.RateLimiter;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.security.TlsContextFactory;
import com.mailsystem.server.service.ServerServices;
import com.mailsystem.server.storage.AttachmentStorage;
import com.mailsystem.server.transfer.UploadManager;
import com.mailsystem.server.transfer.UploadRegistry;

// IOException / Files: tạo thư mục.
import java.io.IOException;
import java.nio.file.Files;
// Connection / SQLException: thử kết nối MySQL.
import java.sql.Connection;
import java.sql.SQLException;
// Bộ chạy tác vụ định kỳ.
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ENTRY POINT của Server: đọc cấu hình, chuẩn bị thư mục dữ liệu, TLS, kiểm
 * tra MySQL, dựng các service, chạy các tác vụ nền (gửi thư hẹn giờ, dọn tệp
 * tải dở), bảng giám sát, rồi mở cổng. Không chứa logic nghiệp vụ.
 */
public final class MailServerApplication {

    // Constructor private: chỉ dùng hàm main.
    private MailServerApplication() {
    }

    /**
     * Khởi động Server.
     *
     * @param args tham số dòng lệnh (không dùng; cấu hình đọc từ config/server.properties)
     */
    public static void main(String[] args) {
        // In thư mục gốc (nơi tìm config/ và data/).
        System.out.println("[MailServer] Thu muc goc: " + ServerConfig.homeDir());
        try {
            // Tạo thư mục lưu tệp đính kèm.
            Files.createDirectories(AttachmentStorage.directory());
            // Xoá tệp tạm còn sót từ lần chạy trước (tải dở lúc Server tắt).
            UploadManager.cleanTempDirectory(ServerConfig.uploadTempDir());
            // Tạo lại thư mục tệp tạm.
            Files.createDirectories(ServerConfig.uploadTempDir());
            // Chuẩn bị tệp nhật ký bảo mật.
            SecurityLog.init(ServerConfig.logsDir());
        } catch (IOException error) {
            fail("Khong tao duoc thu muc du lieu " + ServerConfig.dataDir() + ": " + error.getMessage());
        }

        // Nạp chứng chỉ TLS (lần đầu chạy thì tự tạo bằng keytool).
        TlsContextFactory.ServerTls tls = null;
        try {
            tls = TlsContextFactory.loadOrCreate(ServerConfig.tlsKeystore(), ServerConfig.tlsKeystorePassword());
        } catch (Exception error) {
            fail("Khong khoi tao duoc TLS: " + error.getMessage());
        }
        // In dấu vân tay để người dùng đối chiếu khi Client hỏi "có tin máy chủ này không".
        System.out.println("[TLS] Dau van tay chung chi (SHA-256), dung de doi chieu khi client hoi:");
        System.out.println("      " + tls.fingerprint());

        // Thử mượn một kết nối MySQL để báo lỗi sớm nếu cấu hình sai.
        try (Connection ignored = DatabaseConnection.getConnection()) {
            // In địa chỉ CSDL, bỏ phần tham số sau dấu '?'.
            System.out.println("[MailServer] Ket noi MySQL thanh cong: " + ServerConfig.dbUrl().replaceAll("\\?.*", ""));
        } catch (SQLException error) {
            fail("Khong ket noi duoc MySQL (" + error.getMessage() + "). Kiem tra config/server.properties "
                    + "hoac bien moi truong MAIL_DB_PASSWORD.");
        }
        // Kết nối tới MySQL có mã hoá TLS không.
        String dbCipher = DatabaseConnection.tlsCipher();
        System.out.println("[MailServer] Ket noi MySQL " + (dbCipher == null ? "KHONG ma hoa" : "ma hoa TLS: " + dbCipher));

        // Dựng các service dùng chung.
        ServerServices services = ServerServices.createDefault();
        // Nhóm cũ chưa có địa chỉ @group.local thì sinh địa chỉ cho chúng.
        int filledAddresses = services.group().backfillGroupAddresses();
        if (filledAddresses > 0) {
            System.out.println("[MailServer] Da sinh dia chi @group.local cho " + filledAddresses + " nhom cu");
        }
        // Chế độ xác thực thiết bị (off / want / need).
        String clientAuth = ServerConfig.tlsClientAuth();
        // Tạo đối tượng Server: cổng, số client tối đa, thời gian chờ rảnh (ms), nhà máy socket TLS,
        // bộ định tuyến lệnh, cách tạo RateLimiter cho mỗi kết nối, chế độ xác thực thiết bị.
        MailServer server = new MailServer(ServerConfig.port(), ServerConfig.maxClients(),
                ServerConfig.idleTimeoutSeconds() * 1000, tls.context().getServerSocketFactory(),
                new CommandRouter(services),
                () -> new RateLimiter(ServerConfig.requestsPerSecond(), ServerConfig.requestBurst()), clientAuth);

        // Tác vụ nền: gửi thư hẹn giờ / hết thời gian hoàn tác (mỗi giây), dọn tệp tải lên bỏ dở (mỗi phút).
        // Một thread nền tên "mail-background", daemon để không giữ JVM khi tắt.
        ScheduledExecutorService background = Executors.newScheduledThreadPool(1, task -> {
            Thread thread = new Thread(task, "mail-background");
            thread.setDaemon(true);
            return thread;
        });
        // Mỗi 1 giây (bắt đầu sau 1 giây): gửi các thư đã tới giờ.
        background.scheduleWithFixedDelay(() -> {
            try {
                services.mail().dispatchDue();
            } catch (RuntimeException error) {
                // Bắt lỗi để tác vụ định kỳ không bị dừng hẳn.
                System.err.println("[Outbox] Loi: " + error);
            }
        }, 1, 1, TimeUnit.SECONDS);
        // Mỗi 1 phút: dọn tệp tải lên bỏ dở quá hạn.
        background.scheduleWithFixedDelay(UploadRegistry::sweepExpired, 1, 1, TimeUnit.MINUTES);

        DashboardServer dashboard = null;
        try {
            // Mở cổng lắng nghe.
            server.bind();
        } catch (IOException error) {
            fail("Khong mo duoc cong " + ServerConfig.port() + ": " + error.getMessage());
        }
        // Bật trang giám sát nếu cấu hình cho phép.
        if (ServerConfig.monitorEnabled()) {
            dashboard = new DashboardServer(server, ServerConfig.monitorToken(),
                    DashboardServer.info(ServerConfig.port(), tls.fingerprint(), clientAuth, dbCipher));
            try {
                // Mở trang trên 127.0.0.1 và in đường dẫn (kèm token).
                String url = dashboard.start(ServerConfig.monitorPort());
                System.out.println("[Monitor] Bang giam sat (chi mo duoc tren may nay): " + url);
            } catch (IOException error) {
                System.err.println("[Monitor] Khong mo duoc bang giam sat: " + error.getMessage());
                dashboard = null;
            }
        }

        // Biến final để dùng trong lambda bên dưới.
        DashboardServer finalDashboard = dashboard;
        // Ctrl+C / tắt tiến trình: đóng ServerSocket, mọi kết nối, bảng giám sát và pool MySQL.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            background.shutdownNow();
            if (finalDashboard != null) {
                finalDashboard.stop();
            }
            DatabaseConnection.shutdown();
        }, "mail-shutdown"));
        // Vòng nhận kết nối (chặn tại đây cho tới khi Server dừng).
        server.serve();
    }

    // In lỗi rồi thoát chương trình với mã 1.
    private static void fail(String message) {
        System.err.println("[MailServer] LOI: " + message);
        System.exit(1);
    }
}
