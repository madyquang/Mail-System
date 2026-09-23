package com.mailsystem.client.network;

import com.mailsystem.common.protocol.*;
import com.google.gson.JsonElement;

import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * LOP DUY NHAT chiu trach nhiem giao tiep Socket voi Server.
 * Cac Controller (LoginController, InboxController...) KHONG duoc tu mo Socket
 * rieng - tat ca deu goi qua class nay.
 *
 * VI SAO CAN THREAD RIENG DE DOC (Listener thread):
 * Server co the gui EVENT bat cu luc nao (khong theo yeu cau truoc), nen Client
 * khong the doc kieu "gui request roi doi doc 1 dong tra loi" tuan tu don gian.
 * Thay vao do: 1 thread nen (background) doc lien tuc tu socket; moi dong doc
 * duoc se duoc phan loai:
 *   - RESPONSE -> khop voi requestId dang cho trong pendingRequests, tra ket qua ve.
 *   - EVENT    -> goi eventListener (Controller dang mo se dang ky ham nay de
 *                 cap nhat UI, PHAI boc trong Platform.runLater()).
 *
 * TODO (TV3/TV4): 
 *  - Goi connect() 1 lan duy nhat khi app khoi dong (hoac luc mo man hinh Login).
 *  - Dung sendRequest(...) o moi Controller, KHONG goi socket truc tiep.
 */
public class ServerConnection {

    private static final ServerConnection INSTANCE = new ServerConnection();

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private final Map<String, CompletableFuture<ResponseMessage>> pendingRequests = new ConcurrentHashMap<>();

    // Controller dang active se set ham nay de nhan EVENT (VD: InboxController set khi mo Inbox)
    private Consumer<EventMessage> eventListener;

    private ServerConnection() {
    }

    public static ServerConnection getInstance() {
        return INSTANCE;
    }

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

        // TODO (TV4): dam bao thread nay chay xuyen suot vong doi app (daemon thread).
        Thread listenerThread = new Thread(this::listenLoop);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                dispatchIncomingLine(line);
            }
        } catch (IOException e) {
            System.err.println("[ServerConnection] Mat ket noi toi Server: " + e.getMessage());
            // TODO: co the bao UI hien thong bao "Mat ket noi", thu ket noi lai...
        }
    }

    private void dispatchIncomingLine(String line) {
        MessageType type = ProtocolUtil.peekType(line);

        if (type == MessageType.RESPONSE) {
            ResponseMessage response = ProtocolUtil.fromJson(line, ResponseMessage.class);
            CompletableFuture<ResponseMessage> future = pendingRequests.remove(response.getRequestId());
            if (future != null) {
                future.complete(response);
            }
        } else if (type == MessageType.EVENT) {
            EventMessage event = ProtocolUtil.fromJson(line, EventMessage.class);
            if (eventListener != null) {
                eventListener.accept(event);
                // Luu y: noi goi eventListener.accept() nen tu boc Platform.runLater
                // ben trong chinh listener do (xem vi du trong InboxController).
            }
        }
    }

    /** Dang ky callback nhan EVENT - moi Controller dang "active" nen tu set lai ham nay. */
    public void setEventListener(Consumer<EventMessage> listener) {
        this.eventListener = listener;
    }

    /**
     * Gui 1 REQUEST va tra ve CompletableFuture de Controller .thenAccept(...) xu ly
     * bat dong bo (khong lam dong UI thread cua JavaFX).
     */
    public CompletableFuture<ResponseMessage> sendRequest(Command command, JsonElement payload) {
        String requestId = UUID.randomUUID().toString();
        RequestMessage request = new RequestMessage(requestId, command, payload);

        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        out.println(ProtocolUtil.toJson(request));
        return future;
    }
}
