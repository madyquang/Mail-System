package com.mailsystem.client.network;

import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiểm thử ServerConnection với một "server giả" chạy trên localhost. */
class ServerConnectionTest {

    private ServerSocket fakeServer;
    private ServerConnection connection;

    @BeforeEach
    void setUp() throws IOException {
        fakeServer = new ServerSocket(0);
        connection = new ServerConnection(3600);
    }

    @AfterEach
    void tearDown() throws IOException {
        connection.disconnect();
        fakeServer.close();
    }

    private Socket acceptPeer() throws IOException {
        fakeServer.setSoTimeout(5_000);
        Socket peer = fakeServer.accept();
        peer.setSoTimeout(5_000);
        return peer;
    }

    private static RequestMessage read(FrameReader reader) throws IOException {
        return ProtocolUtil.fromJson(reader.readFrame(), RequestMessage.class);
    }

    @Test
    void matchesResponsesByRequestIdEvenWhenOutOfOrderAndDeliversEvents() throws Exception {
        connection.connect("localhost", fakeServer.getLocalPort());
        CompletableFuture<EventMessage> receivedEvent = new CompletableFuture<>();
        connection.setEventListener(receivedEvent::complete);

        try (Socket peer = acceptPeer()) {
            FrameReader reader = new FrameReader(
                    new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8), 10_000);
            Writer writer = new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8);

            CompletableFuture<ResponseMessage> first = connection.sendRequest(Command.PING, null);
            CompletableFuture<ResponseMessage> second = connection.sendRequest(Command.GET_FOLDERS, null);
            RequestMessage firstRequest = read(reader);
            RequestMessage secondRequest = read(reader);

            // Trả lời request thứ hai trước, chen một EVENT vào giữa.
            JsonObject secondData = new JsonObject();
            secondData.addProperty("which", "second");
            ProtocolUtil.writeFrame(writer, ProtocolUtil.toJson(
                    ResponseMessage.ok(secondRequest.getRequestId(), secondData)));
            ProtocolUtil.writeFrame(writer, ProtocolUtil.toJson(
                    new EventMessage(EventName.NEW_MAIL, new JsonObject())));
            JsonObject firstData = new JsonObject();
            firstData.addProperty("which", "first");
            ProtocolUtil.writeFrame(writer, ProtocolUtil.toJson(
                    ResponseMessage.ok(firstRequest.getRequestId(), firstData)));

            assertEquals("first", first.get(5, TimeUnit.SECONDS).getData().getAsJsonObject().get("which").getAsString());
            assertEquals("second", second.get(5, TimeUnit.SECONDS).getData().getAsJsonObject().get("which").getAsString());
            assertEquals("NEW_MAIL", receivedEvent.get(5, TimeUnit.SECONDS).getEventName());
        }
    }

    @Test
    void lostConnectionFailsPendingRequestsAndNotifies() throws Exception {
        connection.connect("localhost", fakeServer.getLocalPort());
        CompletableFuture<String> disconnectReason = new CompletableFuture<>();
        connection.setDisconnectListener(disconnectReason::complete);

        CompletableFuture<ResponseMessage> pending;
        try (Socket peer = acceptPeer()) {
            pending = connection.sendRequest(Command.PING, null);
            new FrameReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8), 10_000)
                    .readFrame();
            // Server "chết": đóng socket mà không trả lời.
        }

        ExecutionException error = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionLostException.class, error.getCause());
        assertTrue(disconnectReason.get(5, TimeUnit.SECONDS).length() > 0);
        assertFalse(connection.isConnected());

        CompletableFuture<ResponseMessage> afterLoss = connection.sendRequest(Command.PING, null);
        ExecutionException notConnected = assertThrows(ExecutionException.class,
                () -> afterLoss.get(1, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionLostException.class, notConnected.getCause());
    }

    @Test
    void serverNoticeBecomesDisconnectReason() throws Exception {
        connection.connect("localhost", fakeServer.getLocalPort());
        CompletableFuture<String> disconnectReason = new CompletableFuture<>();
        connection.setDisconnectListener(disconnectReason::complete);

        try (Socket peer = acceptPeer();
                Writer writer = new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8)) {
            ProtocolUtil.writeFrame(writer, ProtocolUtil.toJson(
                    ResponseMessage.error(null, "Máy chủ đang quá tải, vui lòng thử lại sau.")));
        }
        assertEquals("Máy chủ đang quá tải, vui lòng thử lại sau.", disconnectReason.get(5, TimeUnit.SECONDS));
    }

    @Test
    void connectFailsFastWhenNothingListens() throws IOException {
        int closedPort;
        try (ServerSocket temporary = new ServerSocket(0)) {
            closedPort = temporary.getLocalPort();
        }
        CompletableFuture<Void> attempt = connection.ensureConnectedAsync("localhost", closedPort);
        ExecutionException error = assertThrows(ExecutionException.class, () -> attempt.get(10, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionLostException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains(Integer.toString(closedPort)));
    }
}
