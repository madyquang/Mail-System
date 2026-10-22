package com.mailsystem.server.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.config.ServerConfig;
import com.mailsystem.server.service.ServerServices;
import com.mailsystem.server.session.ResumeTokens;

import java.time.Duration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Bảng định tuyến: Command → handler. Thêm một command mới chỉ cần thêm một
 * dòng route(...) ở đây (Open/Closed), không phải sửa ClientHandler.
 * Việc kiểm tra đăng nhập được làm tập trung cho mọi route có requiresLogin.
 */
public final class CommandRouter {

    @FunctionalInterface
    public interface Handler {
        ResponseMessage handle(String requestId, JsonElement payload, ConnectionContext context);
    }

    private record Route(Handler handler, boolean requiresLogin) {
    }

    private final Map<Command, Route> routes = new EnumMap<>(Command.class);

    public CommandRouter(ServerServices services) {
        // ---- không cần đăng nhập ----
        open(Command.PING, (id, payload, ctx) -> {
            JsonObject data = new JsonObject();
            data.addProperty("serverTime", System.currentTimeMillis());
            return ResponseMessage.ok(id, data);
        });
        open(Command.REGISTER, (id, payload, ctx) -> services.auth().register(id, payload, ctx.remoteIp()));
        open(Command.LOGIN, (id, payload, ctx) -> startSession(services.auth().login(id, payload, ctx.remoteIp()), ctx));
        // Đăng nhập lại sau khi rớt mạng bằng token một-lần (không cần mật khẩu).
        open(Command.RESUME_SESSION, (id, payload, ctx) -> startSession(services.auth().resume(id, payload, ctx.remoteIp()), ctx));

        // ---- tài khoản ----
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

        for (Command command : Command.values()) {
            if (!routes.containsKey(command)) {
                throw new IllegalStateException("Command chưa có route: " + command);
            }
        }
    }

    /** Đăng nhập thành công (mật khẩu hoặc token): gắn tài khoản vào kết nối + cấp token tiếp tục phiên. */
    private static ResponseMessage startSession(ResponseMessage response, ConnectionContext ctx) {
        if (response.isOk()) {
            ctx.signIn(response.getData().getAsJsonObject().get("accountId").getAsInt());
            issueResumeToken(response, ctx);
        }
        return response;
    }

    private static void issueResumeToken(ResponseMessage response, ConnectionContext ctx) {
        ResumeTokens.Issued token = ResumeTokens.get().issue(ctx.accountId(),
                Duration.ofMinutes(ServerConfig.resumeTokenMinutes()));
        ctx.setResumeTokenHash(token.hash());
        if (response.getData() != null && response.getData().isJsonObject()) {
            response.getData().getAsJsonObject().addProperty("resumeToken", token.token());
        }
    }

    public ResponseMessage dispatch(Command command, String requestId, JsonElement payload, ConnectionContext context) {
        Route route = routes.get(command);
        if (route == null) {
            return ResponseMessage.error(requestId, "Command không được hỗ trợ: " + command);
        }
        if (route.requiresLogin() && context.accountId() == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        return route.handler().handle(requestId, payload, context);
    }

    private void open(Command command, Handler handler) {
        routes.put(command, new Route(handler, false));
    }

    private void secured(Command command, Handler handler) {
        routes.put(command, new Route(handler, true));
    }
}
