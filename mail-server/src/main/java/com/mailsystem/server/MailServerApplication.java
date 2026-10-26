package com.mailsystem.server;

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

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ENTRY POINT của Server: đọc cấu hình, chuẩn bị thư mục dữ liệu, TLS, kiểm
 * tra MySQL, dựng các service, chạy các tác vụ nền (gửi thư hẹn giờ, dọn tệp
 * tải dở), bảng giám sát, rồi mở cổng. Không chứa logic nghiệp vụ.
 */
public final class MailServerApplication {

    private MailServerApplication() {
    }

    public static void main(String[] args) {
        System.out.println("[MailServer] Thu muc goc: " + ServerConfig.homeDir());
        try {
            Files.createDirectories(AttachmentStorage.directory());
            UploadManager.cleanTempDirectory(ServerConfig.uploadTempDir());
            Files.createDirectories(ServerConfig.uploadTempDir());
            SecurityLog.init(ServerConfig.logsDir());
        } catch (IOException error) {
            fail("Khong tao duoc thu muc du lieu " + ServerConfig.dataDir() + ": " + error.getMessage());
        }

        TlsContextFactory.ServerTls tls = null;
        try {
            tls = TlsContextFactory.loadOrCreate(ServerConfig.tlsKeystore(), ServerConfig.tlsKeystorePassword());
        } catch (Exception error) {
            fail("Khong khoi tao duoc TLS: " + error.getMessage());
        }
        System.out.println("[TLS] Dau van tay chung chi (SHA-256), dung de doi chieu khi client hoi:");
        System.out.println("      " + tls.fingerprint());

        try (Connection ignored = DatabaseConnection.getConnection()) {
            System.out.println("[MailServer] Ket noi MySQL thanh cong: " + ServerConfig.dbUrl().replaceAll("\\?.*", ""));
        } catch (SQLException error) {
            fail("Khong ket noi duoc MySQL (" + error.getMessage() + "). Kiem tra config/server.properties "
                    + "hoac bien moi truong MAIL_DB_PASSWORD.");
        }
        String dbCipher = DatabaseConnection.tlsCipher();
        System.out.println("[MailServer] Ket noi MySQL " + (dbCipher == null ? "KHONG ma hoa" : "ma hoa TLS: " + dbCipher));

        ServerServices services = ServerServices.createDefault();
        int filledAddresses = services.group().backfillGroupAddresses();
        if (filledAddresses > 0) {
            System.out.println("[MailServer] Da sinh dia chi @group.local cho " + filledAddresses + " nhom cu");
        }
        String clientAuth = ServerConfig.tlsClientAuth();
        MailServer server = new MailServer(ServerConfig.port(), ServerConfig.maxClients(),
                ServerConfig.idleTimeoutSeconds() * 1000, tls.context().getServerSocketFactory(),
                new CommandRouter(services),
                () -> new RateLimiter(ServerConfig.requestsPerSecond(), ServerConfig.requestBurst()), clientAuth);

        // Tác vụ nền: gửi thư hẹn giờ / hết thời gian hoàn tác (mỗi giây), dọn tệp tải lên bỏ dở (mỗi phút).
        ScheduledExecutorService background = Executors.newScheduledThreadPool(1, task -> {
            Thread thread = new Thread(task, "mail-background");
            thread.setDaemon(true);
            return thread;
        });
        background.scheduleWithFixedDelay(() -> {
            try {
                services.mail().dispatchDue();
            } catch (RuntimeException error) {
                System.err.println("[Outbox] Loi: " + error);
            }
        }, 1, 1, TimeUnit.SECONDS);
        background.scheduleWithFixedDelay(UploadRegistry::sweepExpired, 1, 1, TimeUnit.MINUTES);

        DashboardServer dashboard = null;
        try {
            server.bind();
        } catch (IOException error) {
            fail("Khong mo duoc cong " + ServerConfig.port() + ": " + error.getMessage());
        }
        if (ServerConfig.monitorEnabled()) {
            dashboard = new DashboardServer(server, ServerConfig.monitorToken(),
                    DashboardServer.info(ServerConfig.port(), tls.fingerprint(), clientAuth, dbCipher));
            try {
                String url = dashboard.start(ServerConfig.monitorPort());
                System.out.println("[Monitor] Bang giam sat (chi mo duoc tren may nay): " + url);
            } catch (IOException error) {
                System.err.println("[Monitor] Khong mo duoc bang giam sat: " + error.getMessage());
                dashboard = null;
            }
        }

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
        server.serve();
    }

    private static void fail(String message) {
        System.err.println("[MailServer] LOI: " + message);
        System.exit(1);
    }
}
