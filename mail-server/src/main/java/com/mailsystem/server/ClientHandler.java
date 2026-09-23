package com.mailsystem.server;

import com.google.gson.JsonElement;
import com.mailsystem.common.protocol.*;
import com.mailsystem.server.service.AuthService;
import com.mailsystem.server.service.FolderService;
import com.mailsystem.server.service.GroupService;
import com.mailsystem.server.service.MailService;
import com.mailsystem.server.session.SessionManager;

import java.io.*;
import java.net.Socket;

/**
 * Xử lý 1 kết nối Client duy nhất, chạy trên 1 Thread riêng.
 * Vòng đời: đọc từng dòng JSON (1 dòng = 1 REQUEST) -> dispatch theo Command
 * -> gọi Service tương ứng -> ghi RESPONSE trả về, lặp lại tới khi client ngắt kết nối.
 *
 * ClientHandler cũng chính là "kênh" để Server chủ động gửi EVENT cho Client này
 * (xem sendEvent()) -> SessionManager giữ tham chiếu tới các ClientHandler đang
 * online theo accountId để biết gửi EVENT cho ai khi có thư mới / thay đổi trạng thái.
 *
 * TODO (TV2 - phần Server): implement đầy đủ các case trong switch bên dưới,
 * mỗi case gọi đúng Service tương ứng đã khai báo (đang là stub/interface rỗng).
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private Integer loggedInAccountId = null; // null nghĩa là chưa LOGIN

    // TODO (TV2): inject/khoi tao that cac Service nay (co the qua constructor hoac static factory)
    private final AuthService authService = new AuthService();
    private final MailService mailService = new MailService();
    private final FolderService folderService = new FolderService();
    private final GroupService groupService = new GroupService();

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            String line;
            while ((line = in.readLine()) != null) {
                handleRequestLine(line);
            }
        } catch (IOException e) {
            System.out.println("[ClientHandler] Client ngat ket noi: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void handleRequestLine(String line) {
        RequestMessage request = ProtocolUtil.fromJson(line, RequestMessage.class);
        ResponseMessage response;

        try {
            Command command = request.getCommandEnum();
            JsonElement payload = request.getPayload();

            // TODO (TV2): implement tung nhanh, hien tai deu la placeholder ERROR.
            switch (command) {
                case REGISTER:
                    response = authService.register(request.getRequestId(), payload);
                    break;
                case LOGIN:
                    response = authService.login(request.getRequestId(), payload);
                    // TODO: neu login OK, lay accountId tu response.data, gan vao loggedInAccountId
                    //       va dang ky ClientHandler nay vao SessionManager.register(accountId, this)
                    break;
                case LOGOUT:
                    response = ResponseMessage.ok(request.getRequestId(), null);
                    SessionManager.getInstance().unregister(loggedInAccountId);
                    break;

                case GET_FOLDERS:
                    response = folderService.getFolders(request.getRequestId(), loggedInAccountId);
                    break;
                case GET_MAIL_LIST:
                    response = mailService.getMailList(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case GET_MAIL_DETAIL:
                    response = mailService.getMailDetail(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case SEND_MAIL:
                    response = mailService.sendMail(request.getRequestId(), loggedInAccountId, payload);
                    // TODO: sau khi gui thanh cong, goi SessionManager de day EVENT NEW_MAIL
                    //       toi cac recipient dang online (xem MailService.sendMail va goi
                    //       SessionManager.getInstance().pushEvent(recipientId, eventMessage))
                    break;
                case MARK_READ:
                    response = mailService.markRead(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case DELETE_MAIL:
                    response = mailService.deleteMail(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case SEARCH_MAIL:
                    response = mailService.searchMail(request.getRequestId(), loggedInAccountId, payload);
                    break;

                case CREATE_GROUP:
                    response = groupService.createGroup(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case DELETE_GROUP:
                    response = groupService.deleteGroup(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case ADD_MEMBER:
                    response = groupService.addMember(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case REMOVE_MEMBER:
                    response = groupService.removeMember(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case LEAVE_GROUP:
                    response = groupService.leaveGroup(request.getRequestId(), loggedInAccountId, payload);
                    break;
                case GET_MY_GROUPS:
                    response = groupService.getMyGroups(request.getRequestId(), loggedInAccountId);
                    break;

                default:
                    response = ResponseMessage.error(request.getRequestId(), "Command khong duoc ho tro: " + command);
            }
        } catch (Exception e) {
            response = ResponseMessage.error(request.getRequestId(), "Loi xu ly server: " + e.getMessage());
        }

        sendResponse(response);
    }

    /** Gui 1 RESPONSE ve cho chinh client nay. */
    public synchronized void sendResponse(ResponseMessage response) {
        out.println(ProtocolUtil.toJson(response));
    }

    /** Gui 1 EVENT chu dong (goi tu Service khac, thong qua SessionManager). */
    public synchronized void sendEvent(EventMessage event) {
        out.println(ProtocolUtil.toJson(event));
    }

    private void cleanup() {
        if (loggedInAccountId != null) {
            SessionManager.getInstance().unregister(loggedInAccountId);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
