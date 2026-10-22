package com.mailsystem.server;

import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.net.CommandRouter;
import com.mailsystem.server.net.MailServer;
import com.mailsystem.server.security.RateLimiter;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.security.TlsContextFactory;
import com.mailsystem.server.service.ServerServices;
import com.mailsystem.server.storage.AttachmentStorage;
import com.mailsystem.server.transfer.UploadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * ENTRY POINT của Server: đọc cấu hình, chuẩn bị thư mục dữ liệu, TLS, kiểm
 * tra MySQL, dựng các service rồi mở cổng. Không chứa logic nghiệp vụ.
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

        ServerServices services = ServerServices.createDefault();
        MailServer server = new MailServer(ServerConfig.port(), ServerConfig.maxClients(),
                ServerConfig.idleTimeoutSeconds() * 1000, tls.context().getServerSocketFactory(),
                new CommandRouter(services),
                () -> new RateLimiter(ServerConfig.requestsPerSecond(), ServerConfig.requestBurst()));
        // Ctrl+C / tắt tiến trình: đóng ServerSocket và mọi kết nối một cách gọn gàng.
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "mail-shutdown"));
        try {
            server.bind();
        } catch (IOException error) {
            fail("Khong mo duoc cong " + ServerConfig.port() + ": " + error.getMessage());
        }
        server.serve();
    }

    private static void fail(String message) {
        System.err.println("[MailServer] LOI: " + message);
        System.exit(1);
    }
}
