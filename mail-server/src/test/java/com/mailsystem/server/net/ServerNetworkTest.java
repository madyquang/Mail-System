package com.mailsystem.server.net;

import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.security.RateLimiter;
import com.mailsystem.server.security.TlsContextFactory;
import com.mailsystem.server.service.ServerServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử tầng mạng của Server qua kết nối TLS 1.3 thật trên localhost (keystore
 * tự sinh bằng đúng mã của Server). Các lệnh dùng ở đây không chạm tới MySQL nên
 * test chạy được trên mọi máy.
 */
class ServerNetworkTest {

    private static TlsContextFactory.ServerTls tls;
    private static SSLContext clientContext;

    @TempDir
    Path attachments;

    private MailServer server;
    private int port;

    /** Sinh keystore một lần cho cả lớp test; client chỉ tin đúng chứng chỉ đó. */
    @BeforeAll
    static void createTls() throws Exception {
        Path dir = Files.createTempDirectory("mail-tls-test");
        tls = TlsContextFactory.loadOrCreate(dir.resolve("server.p12"), "");
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("server", tls.certificate());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        clientContext = SSLContext.getInstance("TLSv1.3");
        clientContext.init(null, trust.getTrustManagers(), null);
    }

    @BeforeEach
    void startServer() throws IOException {
        startServer(2, 1_000);
    }

    private void startServer(int maxClients, int requestsPerSecond) throws IOException {
        System.setProperty("attachments.dir", attachments.toString());
        server = new MailServer(0, maxClients, 5_000, tls.context().getServerSocketFactory(),
                new CommandRouter(ServerServices.createDefault()),
                () -> new RateLimiter(requestsPerSecond, requestsPerSecond));
        port = server.bind();
        Thread acceptThread = new Thread(server::serve, "test-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
        System.clearProperty("attachments.dir");
    }

    /** Client TCP tối giản: ghi frame, đọc frame. */
    private final class TestClient implements AutoCloseable {
        final Socket socket;
        final Writer out;
        final FrameReader in;

        TestClient() throws IOException {
            SSLSocket ssl = (SSLSocket) clientContext.getSocketFactory().createSocket("localhost", port);
            ssl.setSoTimeout(5_000);
            ssl.startHandshake();
            socket = ssl;
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            in = new FrameReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    2 * ProtocolConstants.MAX_FRAME_CHARS);
        }

        void sendRaw(String frame) throws IOException {
            ProtocolUtil.writeFrame(out, frame);
        }

        void send(String requestId, Command command) throws IOException {
            sendRaw(ProtocolUtil.toJson(new RequestMessage(requestId, command, null)));
        }

        ResponseMessage receive() throws IOException {
            String frame = in.readFrame();
            return frame == null ? null : ProtocolUtil.fromJson(frame, ResponseMessage.class);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void connectionIsEncryptedWithTls13() throws IOException {
        try (TestClient client = new TestClient()) {
            assertEquals("TLSv1.3", ((SSLSocket) client.socket).getSession().getProtocol());
            assertEquals(tls.fingerprint(), com.mailsystem.common.security.CertificateFingerprints.sha256(
                    (java.security.cert.X509Certificate) ((SSLSocket) client.socket).getSession()
                            .getPeerCertificates()[0]));
        }
    }

    @Test
    void plainTcpClientCannotTalkToServer() throws Exception {
        try (Socket plain = new Socket("localhost", port)) {
            plain.setSoTimeout(5_000);
            ProtocolUtil.writeFrame(new OutputStreamWriter(plain.getOutputStream(), StandardCharsets.UTF_8),
                    ProtocolUtil.toJson(new RequestMessage("x", Command.PING, null)));
            // Server chờ bản ghi TLS, nhận JSON thô -> huỷ bắt tay và đóng kết nối, không bao giờ trả JSON.
            int first = plain.getInputStream().read();
            assertTrue(first == -1 || first == 0x15, "server chỉ được trả bản ghi TLS alert hoặc đóng kết nối");
        }
    }

    @Test
    void clientRejectsUntrustedCertificate() throws Exception {
        // Client chỉ tin MỘT chứng chỉ khác (giống kẻ giả mạo trình chứng chỉ lạ): bắt tay phải thất bại.
        TlsContextFactory.ServerTls other = TlsContextFactory.loadOrCreate(
                Files.createTempDirectory("mail-tls-other").resolve("other.p12"), "");
        KeyStore otherOnly = KeyStore.getInstance(KeyStore.getDefaultType());
        otherOnly.load(null, null);
        otherOnly.setCertificateEntry("other", other.certificate());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(otherOnly);
        SSLContext trustNothing = SSLContext.getInstance("TLSv1.3");
        trustNothing.init(null, trust.getTrustManagers(), null);
        try (SSLSocket socket = (SSLSocket) trustNothing.getSocketFactory().createSocket("localhost", port)) {
            socket.setSoTimeout(5_000);
            org.junit.jupiter.api.Assertions.assertThrows(SSLHandshakeException.class, socket::startHandshake);
        }
    }

    @Test
    void floodingClientIsRateLimited() throws Exception {
        server.stop();
        startServer(2, 5);
        try (TestClient client = new TestClient()) {
            StringBuilder burst = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                burst.append(ProtocolUtil.toJson(new RequestMessage("r" + i, Command.PING, null))).append('\n');
            }
            client.sendRaw(burst.toString().trim());
            int limited = 0;
            for (int i = 0; i < 20; i++) {
                ResponseMessage response = client.receive();
                if (!response.isOk() && response.getMessage().contains("quá nhanh")) {
                    limited++;
                }
            }
            assertTrue(limited >= 10, "phần lớn request vượt hạn mức bị từ chối, thực tế " + limited);
        }
    }

    @Test
    void pingRoundTripKeepsRequestId() throws IOException {
        try (TestClient client = new TestClient()) {
            client.send("req-1", Command.PING);
            ResponseMessage response = client.receive();
            assertTrue(response.isOk());
            assertEquals("req-1", response.getRequestId());
            assertTrue(response.getData().getAsJsonObject().has("serverTime"));
        }
    }

    @Test
    void pipelinedRequestsAreAnsweredInOrderOnOneConnection() throws IOException {
        try (TestClient client = new TestClient()) {
            // Gửi liền 3 request trong một lần ghi, không chờ response.
            String frames = ProtocolUtil.toJson(new RequestMessage("a", Command.PING, null)) + "\n"
                    + ProtocolUtil.toJson(new RequestMessage("b", Command.GET_FOLDERS, null)) + "\n"
                    + ProtocolUtil.toJson(new RequestMessage("c", Command.PING, null));
            client.sendRaw(frames);
            assertEquals("a", client.receive().getRequestId());
            ResponseMessage unauthenticated = client.receive();
            assertEquals("b", unauthenticated.getRequestId());
            assertFalse(unauthenticated.isOk());
            assertEquals("c", client.receive().getRequestId());
        }
    }

    @Test
    void malformedFramesGetErrorsWithoutDroppingConnection() throws IOException {
        try (TestClient client = new TestClient()) {
            client.sendRaw("day khong phai json");
            ResponseMessage notJson = client.receive();
            assertFalse(notJson.isOk());
            assertNull(notJson.getRequestId());

            client.sendRaw("{\"type\":\"REQUEST\",\"requestId\":\"x\",\"command\":\"HACK\"}");
            ResponseMessage unknown = client.receive();
            assertEquals("x", unknown.getRequestId());
            assertTrue(unknown.getMessage().contains("HACK"));

            client.send("still-alive", Command.PING);
            assertTrue(client.receive().isOk());
        }
    }

    @Test
    void oversizedFrameIsRejectedAndConnectionResynchronises() throws IOException {
        try (TestClient client = new TestClient()) {
            client.sendRaw("{\"padding\":\"" + "x".repeat(ProtocolConstants.MAX_FRAME_CHARS + 10) + "\"}");
            ResponseMessage tooLarge = client.receive();
            assertFalse(tooLarge.isOk());
            assertTrue(tooLarge.getMessage().contains("vượt quá"));

            client.send("after", Command.PING);
            assertEquals("after", client.receive().getRequestId());
        }
    }

    @Test
    void commandsRequireLogin() throws IOException {
        try (TestClient client = new TestClient()) {
            client.send("m", Command.GET_MAIL_LIST);
            ResponseMessage response = client.receive();
            assertFalse(response.isOk());
            assertEquals("Bạn chưa đăng nhập.", response.getMessage());
        }
    }

    @Test
    void rejectsConnectionsBeyondLimitWithAnErrorFrame() throws Exception {
        try (TestClient first = new TestClient(); TestClient second = new TestClient()) {
            first.send("1", Command.PING);
            second.send("2", Command.PING);
            first.receive();
            second.receive();

            try (TestClient third = new TestClient()) {
                ResponseMessage busy = third.receive();
                assertFalse(busy.isOk());
                assertTrue(busy.getMessage().contains("quá tải"));
                assertNull(third.receive(), "Server đóng kết nối sau khi báo lỗi");
            }
        }
    }

    @Test
    void closedClientReleasesItsSlot() throws Exception {
        try (TestClient client = new TestClient()) {
            client.send("1", Command.PING);
            client.receive();
            assertEquals(1, server.activeConnections());
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (server.activeConnections() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.activeConnections());
    }
}
