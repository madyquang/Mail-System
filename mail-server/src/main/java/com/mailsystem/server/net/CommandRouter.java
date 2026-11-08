// Gói "net": phần mạng của Server.
package com.mailsystem.server.net;

// Kiểu JSON của Gson.
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// Danh sách lệnh và câu trả lời.
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ResponseMessage;
// Cấu hình (thời hạn token), bộ service, kho token tiếp tục phiên.
import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.service.ServerServices;
import com.mailsystem.server.session.ResumeTokens;

// Duration: thời hạn token.
import java.time.Duration;

// EnumMap: Map có khoá là enum Command.
import java.util.EnumMap;
import java.util.Map;

/**
 * Bảng định tuyến: Command → handler. Thêm một command mới chỉ cần thêm một
 * dòng route(...) ở đây (Open/Closed), không phải sửa ClientHandler.
 * Việc kiểm tra đăng nhập được làm tập trung cho mọi route có requiresLogin.
 */
public final class CommandRouter {

    /** Hàm xử lý một lệnh: nhận mã yêu cầu, dữ liệu, trạng thái kết nối; trả câu trả lời. */
    @FunctionalInterface
    public interface Handler {
        ResponseMessage handle(String requestId, JsonElement payload, ConnectionContext context);
    }

    // Một tuyến: hàm xử lý + có cần đăng nhập không.
    private record Route(Handler handler, boolean requiresLogin) {
    }

    // Bảng lệnh -> tuyến.
    private final Map<Command, Route> routes = new EnumMap<>(Command.class);

    /**
     * Đăng ký tuyến cho mọi lệnh. Mỗi dòng: open(...) = không cần đăng nhập, secured(...) = phải đăng nhập.
     * Mỗi lambda (id, payload, ctx) -> ... chuyển lệnh sang hàm tương ứng của service.
     *
     * @param services bộ service dùng chung
     */
    public CommandRouter(ServerServices services) {
        // ---- không cần đăng nhập ----
        // PING: trả giờ của Server (Client dùng để kiểm tra kết nối còn sống).
        open(Command.PING, (id, payload, ctx) -> {
            JsonObject data = new JsonObject();
            data.addProperty("serverTime", System.currentTimeMillis());
            return ResponseMessage.ok(id, data);
        });
        // Đăng ký tài khoản (truyền IP để giới hạn số lần đăng ký mỗi IP).
        open(Command.REGISTER, (id, payload, ctx) -> services.auth().register(id, payload, ctx.remoteIp()));
        // Đăng nhập; thành công thì startSession gắn tài khoản vào kết nối.
        open(Command.LOGIN, (id, payload, ctx) -> startSession(services.auth().login(id, payload, ctx.remoteIp()), ctx));
        // Đăng nhập lại sau khi rớt mạng bằng token một-lần (không cần mật khẩu).
        open(Command.RESUME_SESSION, (id, payload, ctx) -> startSession(services.auth().resume(id, payload, ctx.remoteIp()), ctx));

        // ---- tài khoản ----
        // Đăng xuất phiên hiện tại.
        secured(Command.LOGOUT, (id, payload, ctx) -> {
            ctx.signOut();
            return ResponseMessage.ok(id, null);
        });
        secured(Command.LOGOUT_ALL, (id, payload, ctx) -> services.account().logoutAll(id, ctx));
        secured(Command.UPDATE_PROFILE, (id, payload, ctx) -> services.account().updateProfile(id, ctx.accountId(), payload));
        secured(Command.CHANGE_PASSWORD, (id, payload, ctx) -> {
            ResponseMessage response = services.account().changePassword(id, ctx, payload);
            if (response.isOk()) {
                // Mọi token cũ đã bị huỷ; cấp token mới cho chính phiên vừa đổi mật khẩu.
                issueResumeToken(response, ctx);
            }
            return response;
        });
        secured(Command.GET_SETTINGS, (id, payload, ctx) -> services.account().getSettings(id, ctx.accountId()));
        secured(Command.UPDATE_SETTINGS, (id, payload, ctx) -> services.account().updateSettings(id, ctx.accountId(), payload));
        secured(Command.TOTP_SETUP, (id, payload, ctx) -> services.account().totpSetup(id, ctx));
        secured(Command.TOTP_ENABLE, (id, payload, ctx) -> services.account().totpEnable(id, ctx, payload));
        secured(Command.TOTP_DISABLE, (id, payload, ctx) -> services.account().totpDisable(id, ctx, payload));

        // ---- thư mục & thư ----
        // accountId luôn lấy từ kết nối (ctx.accountId()), không bao giờ lấy từ payload Client gửi lên.
        secured(Command.GET_FOLDERS, (id, payload, ctx) -> services.folder().getFolders(id, ctx.accountId()));
        secured(Command.CREATE_FOLDER, (id, payload, ctx) -> services.folder().createFolder(id, ctx.accountId(), payload));
        secured(Command.RENAME_FOLDER, (id, payload, ctx) -> services.folder().renameFolder(id, ctx.accountId(), payload));
        secured(Command.DELETE_FOLDER, (id, payload, ctx) -> services.folder().deleteFolder(id, ctx.accountId(), payload));
        secured(Command.GET_MAIL_LIST, (id, payload, ctx) -> services.mail().getMailList(id, ctx.accountId(), payload));
        secured(Command.GET_MAIL_DETAIL, (id, payload, ctx) -> services.mail().getMailDetail(id, ctx.accountId(), payload));
        secured(Command.GET_THREAD, (id, payload, ctx) -> services.mail().getThread(id, ctx.accountId(), payload));
        secured(Command.MOVE_MAIL, (id, payload, ctx) -> services.mail().moveMail(id, ctx.accountId(), payload));
        secured(Command.SEARCH_MAIL, (id, payload, ctx) -> services.mail().searchMail(id, ctx.accountId(), payload));
        secured(Command.SUGGEST_CONTACTS, (id, payload, ctx) -> services.contact().suggest(id, ctx.accountId(), payload));
        secured(Command.MARK_READ, (id, payload, ctx) -> services.mail().markRead(id, ctx.accountId(), payload));
        secured(Command.SET_STARRED, (id, payload, ctx) -> services.mail().setStarred(id, ctx.accountId(), payload));
        secured(Command.DELETE_MAIL, (id, payload, ctx) -> services.mail().deleteMail(id, ctx.accountId(), payload));
        secured(Command.DELETE_TRASH_MAIL, (id, payload, ctx) -> services.mail().deleteTrashMail(id, ctx.accountId(), payload));
        secured(Command.EMPTY_TRASH, (id, payload, ctx) -> services.mail().emptyTrash(id, ctx.accountId()));
        secured(Command.RESTORE_TRASH_MAIL, (id, payload, ctx) -> services.mail().restoreTrashMail(id, ctx.accountId(), payload));

        // ---- soạn & gửi ----
        // Lệnh tải tệp dùng UploadManager của tài khoản (ctx.uploads()).
        secured(Command.UPLOAD_ATTACHMENT_CHUNK,
                (id, payload, ctx) -> services.mail().uploadAttachmentChunk(id, ctx.accountId(), payload, ctx.uploads()));
        secured(Command.UPLOAD_STATUS,
                (id, payload, ctx) -> services.mail().uploadStatus(id, ctx.accountId(), payload, ctx.uploads()));
        secured(Command.GET_SCHEDULED, (id, payload, ctx) -> services.mail().getScheduled(id, ctx.accountId()));
        secured(Command.CANCEL_SCHEDULED, (id, payload, ctx) -> services.mail().cancelScheduled(id, ctx.accountId(), payload));
        secured(Command.DOWNLOAD_ATTACHMENT, (id, payload, ctx) -> services.mail().downloadAttachment(id, ctx.accountId(), payload));
        secured(Command.SEND_MAIL, (id, payload, ctx) -> services.mail().sendMail(id, ctx.accountId(), payload, ctx.uploads()));
        secured(Command.SAVE_DRAFT, (id, payload, ctx) -> services.draft().save(id, ctx.accountId(), payload));
        secured(Command.GET_DRAFTS, (id, payload, ctx) -> services.draft().list(id, ctx.accountId()));
        secured(Command.DELETE_DRAFTS, (id, payload, ctx) -> services.draft().delete(id, ctx.accountId(), payload));

        // ---- nhóm thư ----
        secured(Command.CREATE_GROUP, (id, payload, ctx) -> services.group().createGroup(id, ctx.accountId(), payload));
        secured(Command.DELETE_GROUP, (id, payload, ctx) -> services.group().deleteGroup(id, ctx.accountId(), payload));
        secured(Command.ADD_MEMBER, (id, payload, ctx) -> services.group().addMember(id, ctx.accountId(), payload));
        secured(Command.REMOVE_MEMBER, (id, payload, ctx) -> services.group().removeMember(id, ctx.accountId(), payload));
        secured(Command.LEAVE_GROUP, (id, payload, ctx) -> services.group().leaveGroup(id, ctx.accountId(), payload));
        secured(Command.GET_MY_GROUPS, (id, payload, ctx) -> services.group().getMyGroups(id, ctx.accountId()));

        // Kiểm tra lúc khởi động: lệnh nào trong enum Command chưa có tuyến thì báo lỗi ngay
        // (không để tới lúc Client gọi mới phát hiện).
        for (Command command : Command.values()) {
            if (!routes.containsKey(command)) {
                throw new IllegalStateException("Command chưa có route: " + command);
            }
        }
    }

    /** Đăng nhập thành công (mật khẩu hoặc token): gắn tài khoản vào kết nối + cấp token tiếp tục phiên. */
    private static ResponseMessage startSession(ResponseMessage response, ConnectionContext ctx) {
        if (response.isOk()) {
            // Đọc accountId trong dữ liệu trả về và gắn vào kết nối.
            ctx.signIn(response.getData().getAsJsonObject().get("accountId").getAsInt());
            // Cấp token mới và đưa vào câu trả lời.
            issueResumeToken(response, ctx);
        }
        return response;
    }

    // Cấp token tiếp tục phiên cho kết nối và thêm trường "resumeToken" vào câu trả lời.
    private static void issueResumeToken(ResponseMessage response, ConnectionContext ctx) {
        // Thời hạn token lấy từ cấu hình (phút).
        ResumeTokens.Issued token = ResumeTokens.get().issue(ctx.accountId(),
                Duration.ofMinutes(ServerConfig.resumeTokenMinutes()));
        // Kết nối nhớ hash để huỷ token khi LOGOUT.
        ctx.setResumeTokenHash(token.hash());
        // Token gốc gửi cho Client.
        if (response.getData() != null && response.getData().isJsonObject()) {
            response.getData().getAsJsonObject().addProperty("resumeToken", token.token());
        }
    }

    /**
     * Chuyển một lệnh tới hàm xử lý.
     *
     * @param command   lệnh
     * @param requestId mã yêu cầu
     * @param payload   dữ liệu kèm theo
     * @param context   trạng thái kết nối
     * @return câu trả lời
     */
    public ResponseMessage dispatch(Command command, String requestId, JsonElement payload, ConnectionContext context) {
        // Tìm tuyến của lệnh.
        Route route = routes.get(command);
        if (route == null) {
            return ResponseMessage.error(requestId, "Command không được hỗ trợ: " + command);
        }
        // Lệnh cần đăng nhập mà kết nối chưa đăng nhập: từ chối (kiểm tra tập trung một chỗ).
        if (route.requiresLogin() && context.accountId() == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        // Gọi hàm xử lý.
        return route.handler().handle(requestId, payload, context);
    }

    // Đăng ký tuyến không cần đăng nhập.
    private void open(Command command, Handler handler) {
        routes.put(command, new Route(handler, false));
    }

    // Đăng ký tuyến bắt buộc đăng nhập.
    private void secured(Command command, Handler handler) {
        routes.put(command, new Route(handler, true));
    }
}
