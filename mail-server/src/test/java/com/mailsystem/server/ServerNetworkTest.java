package com.mailsystem.server;

import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử tầng mạng của Server qua kết nối TCP thật trên localhost. Các lệnh
 * dùng ở đây không chạm tới MySQL nên test chạy được trên mọi máy.
 */
class ServerNetworkTest {

    @TempDir
    Path attachments;

    private MailServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        System.setProperty("attachments.dir", attachments.toString());
        server = new MailServer(0, 2, 5_000);
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
            socket = new Socket("localhost", port);
            socket.setSoTimeout(5_000);
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
