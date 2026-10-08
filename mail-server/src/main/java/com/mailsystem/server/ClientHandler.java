package com.mailsystem.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.FrameTooLargeException;
import com.mailsystem.common.protocol.MessageType;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.service.AuthService;
import com.mailsystem.server.service.FolderService;
import com.mailsystem.server.service.GroupService;
import com.mailsystem.server.service.MailService;
import com.mailsystem.server.session.ClientSession;
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.transfer.UploadManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Xử lý MỘT kết nối Client. Mỗi kết nối dùng hai thread:
 *
 * - Thread đọc (run()): đọc từng frame JSON (1 frame = 1 REQUEST), gọi Service
 *   và xếp RESPONSE vào hàng đợi gửi. Các request của một client được xử lý
 *   tuần tự theo đúng thứ tự gửi.
 * - Thread ghi (writeLoop()): lấy frame từ hàng đợi và ghi xuống socket.
 *
 * Vì sao cần hàng đợi gửi: EVENT được đẩy từ thread của client KHÁC (VD: A gửi
 * thư thì thread của A đẩy NEW_MAIL tới B). Nếu thread của A ghi thẳng vào
 * socket của B mà B mạng chậm (bộ đệm gửi TCP đầy), thread của A bị chặn theo.
 * Với hàng đợi, sendEvent() chỉ offer() rồi trả về ngay; nếu hàng đợi đầy
 * (client không đọc kịp) thì kết nối đó bị đóng thay vì làm nghẽn cả Server.
 */
public class ClientHandler implements Runnable, ClientSession {

    private static final int OUTBOUND_QUEUE_CAPACITY = 256;
    /** Frame đặc biệt báo thread ghi: gửi nốt hàng đợi rồi đóng socket. */
    private static final String END_OF_STREAM = "\u0000END";
    private static final Set<Command> PUBLIC_COMMANDS = EnumSet.of(Command.REGISTER, Command.LOGIN, Command.PING);

    private final Socket socket;
    private final String remoteAddress;
    private final Runnable onClose;
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final UploadManager uploads = new UploadManager(ServerConfig.uploadTempDir());

    private volatile Integer loggedInAccountId; // null nghĩa là chưa LOGIN

    private final AuthService authService = new AuthService();
    private final MailService mailService = new MailService();
    private final FolderService folderService = new FolderService();
    private final GroupService groupService = new GroupService();

    public ClientHandler(Socket socket) {
        this(socket, () -> {
        });
    }

    public ClientHandler(Socket socket, Runnable onClose) {
        this.socket = socket;
        this.remoteAddress = socket.getRemoteSocketAddress() == null
                ? "unknown"
                : socket.getRemoteSocketAddress().toString();
        this.onClose = onClose;
    }

    @Override
    public void run() {
        Thread writer = new Thread(this::writeLoop, "mail-writer-" + remoteAddress);
        writer.setDaemon(true);
        writer.start();

        String reason = "client dong ket noi";
        try {
            FrameReader reader = new FrameReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    ProtocolConstants.MAX_FRAME_CHARS);
            while (!closed.get()) {
                String frame;
                try {
                    frame = reader.readFrame();
                } catch (FrameTooLargeException error) {
                    enqueue(ResponseMessage.error(null, "Gói tin vượt quá giới hạn "
                            + ProtocolConstants.MAX_FRAME_CHARS + " ký tự và đã bị bỏ qua."));
                    continue;
                }
                if (frame == null) {
                    break; // nhận FIN: client đã đóng chiều gửi
                }
                if (!frame.isBlank()) {
                    handleFrame(frame);
                }
            }
        } catch (SocketTimeoutException error) {
            reason = "khong nhan duoc du lieu trong " + ServerConfig.idleTimeoutSeconds() + " giay";
        } catch (IOException error) {
            reason = closed.get() ? "server dong ket noi" : "loi mang: " + error.getMessage();
        } finally {
            System.out.println("[ClientHandler] Dong ket noi " + remoteAddress + " (" + reason + ")");
            close();
        }
    }

    private void handleFrame(String frame) {
        RequestMessage request;
        try {
            request = ProtocolUtil.fromJson(frame, RequestMessage.class);
        } catch (JsonParseException error) {
            enqueue(ResponseMessage.error(null, "Gói tin không phải JSON hợp lệ."));
            return;
        }
        if (request == null || !MessageType.REQUEST.name().equals(request.getType())) {
            enqueue(ResponseMessage.error(null, "Gói tin phải có type = REQUEST."));
            return;
        }

        String requestId = request.getRequestId();
        Command command;
        try {
            command = request.getCommandEnum();
        } catch (IllegalArgumentException | NullPointerException error) {
            enqueue(ResponseMessage.error(requestId, "Command không được hỗ trợ: " + request.getCommand()));
            return;
        }
        if (!PUBLIC_COMMANDS.contains(command) && loggedInAccountId == null) {
            enqueue(ResponseMessage.error(requestId, "Bạn chưa đăng nhập."));
            return;
        }

        ResponseMessage response;
        try {
            response = dispatch(requestId, command, request.getPayload());
        } catch (RuntimeException error) {
            System.err.println("[ClientHandler] " + command + " loi (requestId=" + requestId + "): " + error);
            error.printStackTrace(System.err);
            response = ResponseMessage.error(requestId, "Lỗi xử lý yêu cầu trên máy chủ.");
        }
        enqueue(response);
    }

    private ResponseMessage dispatch(String requestId, Command command, JsonElement payload) {
        Integer accountId = loggedInAccountId;
        switch (command) {
            case PING: {
                JsonObject data = new JsonObject();
                data.addProperty("serverTime", System.currentTimeMillis());
                return ResponseMessage.ok(requestId, data);
            }
            case REGISTER:
                return authService.register(requestId, payload);
            case LOGIN: {
                ResponseMessage response = authService.login(requestId, payload);
                if (response.isOk()) {
                    JsonElement data = response.getData();
                    if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("accountId")) {
                        return ResponseMessage.error(requestId, "Phản hồi đăng nhập không hợp lệ.");
                    }
                    switchAccount(data.getAsJsonObject().get("accountId").getAsInt());
                }
                return response;
            }
            case LOGOUT:
                switchAccount(null);
                return ResponseMessage.ok(requestId, null);

            case GET_FOLDERS:
                return folderService.getFolders(requestId, accountId);
            case GET_MAIL_LIST:
                return mailService.getMailList(requestId, accountId, payload);
            case GET_MAIL_DETAIL:
                return mailService.getMailDetail(requestId, accountId, payload);
            case DOWNLOAD_ATTACHMENT:
                return mailService.downloadAttachment(requestId, accountId, payload);
            case UPLOAD_ATTACHMENT_CHUNK:
                return mailService.uploadAttachmentChunk(requestId, accountId, payload, uploads);
            case SEND_MAIL:
                return mailService.sendMail(requestId, accountId, payload, uploads);
            case MARK_READ:
                return mailService.markRead(requestId, accountId, payload);
            case DELETE_MAIL:
                return mailService.deleteMail(requestId, accountId, payload);
            case DELETE_TRASH_MAIL:
                return mailService.deleteTrashMail(requestId, accountId, payload);
            case EMPTY_TRASH:
                return mailService.emptyTrash(requestId, accountId);
            case RESTORE_TRASH_MAIL:
                return mailService.restoreTrashMail(requestId, accountId, payload);
            case SEARCH_MAIL:
                return mailService.searchMail(requestId, accountId, payload);

            case CREATE_GROUP:
                return groupService.createGroup(requestId, accountId, payload);
            case DELETE_GROUP:
                return groupService.deleteGroup(requestId, accountId, payload);
            case ADD_MEMBER:
                return groupService.addMember(requestId, accountId, payload);
            case REMOVE_MEMBER:
                return groupService.removeMember(requestId, accountId, payload);
            case LEAVE_GROUP:
                return groupService.leaveGroup(requestId, accountId, payload);
            case GET_MY_GROUPS:
                return groupService.getMyGroups(requestId, accountId);

            default:
                return ResponseMessage.error(requestId, "Command không được hỗ trợ: " + command);
        }
    }

    /** Đổi tài khoản của phiên (đăng nhập/đăng xuất) và cập nhật SessionManager. */
    private void switchAccount(Integer newAccountId) {
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        uploads.discardAll();
        loggedInAccountId = newAccountId;
        if (newAccountId != null) {
            SessionManager.getInstance().register(newAccountId, this);
        }
    }

    /** Gửi EVENT chủ động (gọi từ SessionManager, có thể từ thread của client khác). */
    @Override
    public void sendEvent(EventMessage event) {
        enqueue(event);
    }

    private void enqueue(Object message) {
        if (closed.get()) {
            return;
        }
        if (!outbound.offer(ProtocolUtil.toJson(message))) {
            System.err.println("[ClientHandler] Hang doi gui cua " + remoteAddress
                    + " day (client khong doc kip), dong ket noi.");
            forceClose();
        }
    }

    private void writeLoop() {
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String frame = outbound.take();
                if (END_OF_STREAM.equals(frame)) {
                    break;
                }
                writer.write(frame);
                writer.write('\n');
                // Gộp nhiều frame đang chờ vào một lần flush (ít segment TCP hơn).
                if (outbound.isEmpty()) {
                    writer.flush();
                }
            }
            writer.flush();
        } catch (IOException error) {
            if (!closed.get()) {
                System.err.println("[ClientHandler] Khong ghi duoc toi " + remoteAddress + ": " + error.getMessage());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            close();
            closeSocketQuietly();
        }
    }

    /**
     * Đóng kết nối một cách "lịch sự": gỡ phiên, xoá upload dở dang, rồi để
     * thread ghi gửi nốt các frame đang chờ trước khi đóng socket.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SessionManager.getInstance().unregister(loggedInAccountId, this);
        uploads.discardAll();
        if (!outbound.offer(END_OF_STREAM)) {
            closeSocketQuietly();
        }
        onClose.run();
    }

    /** Đóng ngay lập tức (dừng Server hoặc client không đọc kịp). */
    public void forceClose() {
        close();
        closeSocketQuietly();
    }

    private void closeSocketQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
