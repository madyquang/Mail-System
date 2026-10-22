package com.mailsystem.server.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.service.ServerServices;

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
        open(Command.LOGIN, (id, payload, ctx) -> {
            ResponseMessage response = services.auth().login(id, payload, ctx.remoteIp());
            if (response.isOk()) {
                ctx.signIn(response.getData().getAsJsonObject().get("accountId").getAsInt());
            }
            return response;
        });

        // ---- tài khoản ----
        secured(Command.LOGOUT, (id, payload, ctx) -> {
            ctx.signOut();
            return ResponseMessage.ok(id, null);
        });
        secured(Command.LOGOUT_ALL, (id, payload, ctx) -> services.account().logoutAll(id, ctx));
        secured(Command.UPDATE_PROFILE, (id, payload, ctx) -> services.account().updateProfile(id, ctx.accountId(), payload));
        secured(Command.CHANGE_PASSWORD, (id, payload, ctx) -> services.account().changePassword(id, ctx, payload));

        // ---- thư mục & thư ----
        secured(Command.GET_FOLDERS, (id, payload, ctx) -> services.folder().getFolders(id, ctx.accountId()));
        secured(Command.GET_MAIL_LIST, (id, payload, ctx) -> services.mail().getMailList(id, ctx.accountId(), payload));
        secured(Command.GET_MAIL_DETAIL, (id, payload, ctx) -> services.mail().getMailDetail(id, ctx.accountId(), payload));
        secured(Command.SEARCH_MAIL, (id, payload, ctx) -> services.mail().searchMail(id, ctx.accountId(), payload));
        secured(Command.MARK_READ, (id, payload, ctx) -> services.mail().markRead(id, ctx.accountId(), payload));
        secured(Command.SET_STARRED, (id, payload, ctx) -> services.mail().setStarred(id, ctx.accountId(), payload));
        secured(Command.DELETE_MAIL, (id, payload, ctx) -> services.mail().deleteMail(id, ctx.accountId(), payload));
        secured(Command.DELETE_TRASH_MAIL, (id, payload, ctx) -> services.mail().deleteTrashMail(id, ctx.accountId(), payload));
        secured(Command.EMPTY_TRASH, (id, payload, ctx) -> services.mail().emptyTrash(id, ctx.accountId()));
        secured(Command.RESTORE_TRASH_MAIL, (id, payload, ctx) -> services.mail().restoreTrashMail(id, ctx.accountId(), payload));

        // ---- soạn & gửi ----
        secured(Command.UPLOAD_ATTACHMENT_CHUNK,
                (id, payload, ctx) -> services.mail().uploadAttachmentChunk(id, ctx.accountId(), payload, ctx.uploads()));
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
