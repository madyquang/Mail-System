package com.mailsystem.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mailsystem.client.app.Reconnector;
import com.mailsystem.client.app.Session;
import com.mailsystem.client.network.ConnectionLostException;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.AccountSettings;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.Contact;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailGroupDTO;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.common.model.ScheduledMail;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Lớp bọc có kiểu (typed) quanh giao thức: Controller gọi api.markRead(ids, true)
 * thay vì tự dựng JSON. Mọi chi tiết payload nằm ở MỘT nơi, khớp với
 * docs/PROTOCOL.md. RESPONSE lỗi được chuyển thành ApiException.
 */
public final class MailApi {

    private static final MailApi INSTANCE = new MailApi(ServerConnection.getInstance());

    private final ServerConnection connection;

    MailApi(ServerConnection connection) {
        this.connection = connection;
    }

    public static MailApi get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ tài khoản

    /** Kết quả đăng nhập: tài khoản + token một-lần để tự đăng nhập lại khi rớt mạng. */
    public record LoginResult(Account account, String resumeToken) {
    }

    /** totpCode: mã 6 số của ứng dụng xác thực (null nếu tài khoản chưa bật 2FA). */
    public CompletableFuture<LoginResult> login(String email, String password, String totpCode) {
        JsonObject payload = object("email", email, "password", password);
        if (totpCode != null) {
            payload.addProperty("totpCode", totpCode);
        }
        return call(Command.LOGIN, payload, MailApi::loginResult);
    }

    /** Đăng nhập lại bằng token (sau khi kết nối lại). */
    public CompletableFuture<LoginResult> resume(String resumeToken) {
        return call(Command.RESUME_SESSION, object("resumeToken", resumeToken), MailApi::loginResult);
    }

    private static LoginResult loginResult(JsonElement data) {
        JsonObject object = data.getAsJsonObject();
        return new LoginResult(as(data, Account.class),
                object.has("resumeToken") ? object.get("resumeToken").getAsString() : null);
    }

    public CompletableFuture<Account> register(String email, String displayName, String password) {
        return call(Command.REGISTER, object("email", email, "displayName", displayName, "password", password),
                data -> as(data, Account.class));
    }

    public CompletableFuture<Void> logout() {
        return call(Command.LOGOUT, null, data -> null);
    }

    public CompletableFuture<Integer> logoutAll() {
        return call(Command.LOGOUT_ALL, null, data -> data.getAsJsonObject().get("revokedSessions").getAsInt());
    }

    public CompletableFuture<String> updateProfile(String displayName) {
        return call(Command.UPDATE_PROFILE, object("displayName", displayName),
                data -> data.getAsJsonObject().get("displayName").getAsString());
    }

    /** Token cũ bị huỷ khi đổi mật khẩu; Server cấp token mới trong cùng phản hồi. */
    public CompletableFuture<Integer> changePassword(String currentPassword, String newPassword) {
        return call(Command.CHANGE_PASSWORD, object("currentPassword", currentPassword, "newPassword", newPassword),
                data -> {
                    JsonObject object = data.getAsJsonObject();
                    if (object.has("resumeToken")) {
                        Session.setResumeToken(object.get("resumeToken").getAsString());
                    }
                    return object.get("revokedSessions").getAsInt();
                });
    }

    public CompletableFuture<AccountSettings> settings() {
        return call(Command.GET_SETTINGS, null, data -> as(data, AccountSettings.class));
    }

    public CompletableFuture<Void> setShareReadReceipts(boolean share) {
        JsonObject payload = new JsonObject();
        payload.addProperty("shareReadReceipts", share);
        return call(Command.UPDATE_SETTINGS, payload, data -> null);
    }

    public record TotpSetup(String secret, String otpauthUri) {
    }

    /** Tạo khoá bí mật mới (chưa bật); người dùng quét QR rồi xác nhận bằng một mã. */
    public CompletableFuture<TotpSetup> totpSetup() {
        return call(Command.TOTP_SETUP, null, data -> new TotpSetup(
                data.getAsJsonObject().get("secret").getAsString(),
                data.getAsJsonObject().get("otpauthUri").getAsString()));
    }

    public CompletableFuture<Void> totpEnable(String code) {
        return call(Command.TOTP_ENABLE, object("code", code), data -> null);
    }

    public CompletableFuture<Void> totpDisable(String password, String code) {
        return call(Command.TOTP_DISABLE, object("password", password, "code", code), data -> null);
    }

    // ------------------------------------------------------------------ thư

    public CompletableFuture<List<Folder>> folders() {
        return call(Command.GET_FOLDERS, null, data -> list(data, new TypeToken<List<Folder>>() { }.getType()));
    }

    /** threaded: gộp thư cùng hội thoại thành một dòng (thư mới nhất đại diện). */
    public CompletableFuture<List<MailSummary>> mailList(int folderId, boolean threaded) {
        JsonObject payload = new JsonObject();
        payload.addProperty("folderId", folderId);
        payload.addProperty("threaded", threaded);
        return call(Command.GET_MAIL_LIST, payload, MailApi::summaries);
    }

    /** Mọi thư của hội thoại, cũ trước mới sau. */
    public CompletableFuture<List<MailDetail>> thread(int threadId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("threadId", threadId);
        return call(Command.GET_THREAD, payload, data -> list(data, new TypeToken<List<MailDetail>>() { }.getType()));
    }

    public CompletableFuture<Integer> createFolder(String name) {
        return call(Command.CREATE_FOLDER, object("name", name), data -> data.getAsJsonObject().get("folderId").getAsInt());
    }

    public CompletableFuture<Void> renameFolder(int folderId, String name) {
        JsonObject payload = object("name", name);
        payload.addProperty("folderId", folderId);
        return call(Command.RENAME_FOLDER, payload, data -> null);
    }

    /** @return số thư đã chuyển vào Thùng rác */
    public CompletableFuture<Integer> deleteFolder(int folderId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("folderId", folderId);
        return call(Command.DELETE_FOLDER, payload, data -> data.getAsJsonObject().get("movedToTrash").getAsInt());
    }

    public CompletableFuture<Integer> moveMail(Collection<Integer> entryIds, int folderId) {
        JsonObject payload = entries(entryIds);
        payload.addProperty("folderId", folderId);
        return call(Command.MOVE_MAIL, payload, MailApi::processed);
    }

    public CompletableFuture<List<MailSummary>> starred() {
        return call(Command.GET_MAIL_LIST, object("view", "STARRED"), MailApi::summaries);
    }

    public CompletableFuture<List<MailSummary>> search(Integer folderId, String keyword, String fromFilter) {
        JsonObject payload = object("keyword", keyword, "fromFilter", fromFilter);
        if (folderId != null) {
            payload.addProperty("folderId", folderId);
        }
        return call(Command.SEARCH_MAIL, payload, MailApi::summaries);
    }

    /** Gợi ý người nhận khi gõ: nhóm (nếu includeGroups) rồi tới người, tối đa limit kết quả. */
    public CompletableFuture<List<Contact>> suggestContacts(String query, boolean includeGroups, int limit) {
        JsonObject payload = object("query", query);
        payload.addProperty("includeGroups", includeGroups);
        payload.addProperty("limit", limit);
        return call(Command.SUGGEST_CONTACTS, payload, data -> list(data, new TypeToken<List<Contact>>() { }.getType()));
    }

    public CompletableFuture<MailDetail> detail(int mailId, int entryId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("mailId", mailId);
        if (entryId > 0) {
            payload.addProperty("entryId", entryId);
        }
        return call(Command.GET_MAIL_DETAIL, payload, data -> as(data, MailDetail.class));
    }

    public CompletableFuture<Integer> markRead(Collection<Integer> entryIds, boolean read) {
        JsonObject payload = entries(entryIds);
        payload.addProperty("read", read);
        return call(Command.MARK_READ, payload, MailApi::processed);
    }

    public CompletableFuture<Integer> setStarred(Collection<Integer> entryIds, boolean starred) {
        JsonObject payload = entries(entryIds);
        payload.addProperty("starred", starred);
        return call(Command.SET_STARRED, payload, MailApi::processed);
    }

    public CompletableFuture<Integer> moveToTrash(Collection<Integer> entryIds) {
        return call(Command.DELETE_MAIL, entries(entryIds), MailApi::processed);
    }

    public CompletableFuture<Integer> restore(Collection<Integer> entryIds) {
        return call(Command.RESTORE_TRASH_MAIL, entries(entryIds), MailApi::processed);
    }

    public CompletableFuture<Integer> deleteForever(Collection<Integer> entryIds) {
        return call(Command.DELETE_TRASH_MAIL, entries(entryIds), MailApi::processed);
    }

    public CompletableFuture<Integer> emptyTrash() {
        return call(Command.EMPTY_TRASH, null, JsonElement::getAsInt);
    }

    // ------------------------------------------------------------------ soạn & gửi

    /** Thư cần gửi. uploadIds: tệp đã tải lên; forwardAttachmentIds: tệp của thư gốc khi chuyển tiếp. */
    /** delaySeconds: chờ vài giây để hoàn tác; sendAt: hẹn giờ (giờ máy chủ). Cả hai null = gửi ngay. */
    public record OutgoingMail(List<String> to, List<String> cc, List<String> bcc, String subject, String body,
            List<String> uploadIds, List<Integer> forwardAttachmentIds, Integer draftId, Integer inReplyTo,
            Integer delaySeconds, LocalDateTime sendAt) {
    }

    /** Kết quả gửi: mailId nếu đã gửi ngay, hoặc outboxId + sendAt nếu đang chờ (hoàn tác / hẹn giờ). */
    public record SendResult(Integer mailId, Integer outboxId, String sendAt) {
        public boolean queued() {
            return outboxId != null;
        }
    }

    public CompletableFuture<SendResult> send(OutgoingMail mail) {
        JsonObject payload = new JsonObject();
        payload.add("to", strings(mail.to()));
        payload.add("cc", strings(mail.cc()));
        payload.add("bcc", strings(mail.bcc()));
        payload.addProperty("subject", mail.subject());
        payload.addProperty("body", mail.body());
        JsonArray attachments = new JsonArray();
        for (String uploadId : mail.uploadIds()) {
            attachments.add(object("uploadId", uploadId));
        }
        payload.add("attachments", attachments);
        JsonArray forwarded = new JsonArray();
        mail.forwardAttachmentIds().forEach(forwarded::add);
        payload.add("forwardAttachmentIds", forwarded);
        if (mail.draftId() != null) {
            payload.addProperty("draftId", mail.draftId());
        }
        if (mail.inReplyTo() != null) {
            payload.addProperty("inReplyTo", mail.inReplyTo());
        }
        if (mail.sendAt() != null) {
            payload.addProperty("sendAt", mail.sendAt().withNano(0).toString());
        } else if (mail.delaySeconds() != null) {
            payload.addProperty("delaySeconds", mail.delaySeconds());
        }
        return call(Command.SEND_MAIL, payload, data -> {
            JsonObject object = data.getAsJsonObject();
            return object.has("outboxId")
                    ? new SendResult(null, object.get("outboxId").getAsInt(), object.get("sendAt").getAsString())
                    : new SendResult(object.get("mailId").getAsInt(), null, null);
        });
    }

    public CompletableFuture<List<ScheduledMail>> scheduled() {
        return call(Command.GET_SCHEDULED, null, data -> list(data, new TypeToken<List<ScheduledMail>>() { }.getType()));
    }

    /** Huỷ thư đang chờ; trả lại nội dung (to, cc, bcc là mảng; subject, body, attachmentCount). */
    public CompletableFuture<JsonObject> cancelScheduled(int outboxId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("outboxId", outboxId);
        return call(Command.CANCEL_SCHEDULED, payload, JsonElement::getAsJsonObject);
    }

    /** Server đã nhận bao nhiêu byte của một lần tải lên (để tải tiếp sau khi rớt mạng). */
    public CompletableFuture<UploadProgress> uploadStatus(String uploadId) {
        return call(Command.UPLOAD_STATUS, object("uploadId", uploadId), data -> new UploadProgress(
                data.getAsJsonObject().get("uploadId").getAsString(),
                data.getAsJsonObject().get("receivedBytes").getAsLong()));
    }

    public record UploadProgress(String uploadId, long receivedBytes) {
    }

    public CompletableFuture<UploadProgress> uploadChunk(String uploadId, String filename, long sizeBytes, long offset,
            String dataBase64) {
        JsonObject payload = object("filename", filename, "dataBase64", dataBase64);
        if (uploadId != null) {
            payload.addProperty("uploadId", uploadId);
        }
        payload.addProperty("sizeBytes", sizeBytes);
        payload.addProperty("offset", offset);
        return call(Command.UPLOAD_ATTACHMENT_CHUNK, payload, data -> new UploadProgress(
                data.getAsJsonObject().get("uploadId").getAsString(),
                data.getAsJsonObject().get("receivedBytes").getAsLong()));
    }

    public CompletableFuture<Attachment> downloadChunk(int attachmentId, long offset) {
        JsonObject payload = new JsonObject();
        payload.addProperty("attachmentId", attachmentId);
        payload.addProperty("offset", offset);
        return call(Command.DOWNLOAD_ATTACHMENT, payload, data -> as(data, Attachment.class));
    }

    public CompletableFuture<Integer> saveDraft(Draft draft) {
        JsonObject payload = object("to", draft.getTo(), "cc", draft.getCc(), "bcc", draft.getBcc(),
                "subject", draft.getSubject(), "body", draft.getBody());
        if (draft.getDraftId() > 0) {
            payload.addProperty("draftId", draft.getDraftId());
        }
        return call(Command.SAVE_DRAFT, payload, data -> data.getAsJsonObject().get("draftId").getAsInt());
    }

    public CompletableFuture<List<Draft>> drafts() {
        return call(Command.GET_DRAFTS, null, data -> list(data, new TypeToken<List<Draft>>() { }.getType()));
    }

    public CompletableFuture<Integer> deleteDrafts(Collection<Integer> draftIds) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        draftIds.forEach(ids::add);
        payload.add("draftIds", ids);
        return call(Command.DELETE_DRAFTS, payload, data -> data.getAsJsonObject().get("deleted").getAsInt());
    }

    // ------------------------------------------------------------------ nhóm

    public CompletableFuture<List<MailGroupDTO>> groups() {
        return call(Command.GET_MY_GROUPS, null, data -> list(data, new TypeToken<List<MailGroupDTO>>() { }.getType()));
    }

    /** address: phần trước @group.local (rỗng = Server tự sinh từ tên nhóm). */
    public CompletableFuture<Integer> createGroup(String name, String address) {
        return call(Command.CREATE_GROUP, object("groupName", name, "address", address),
                data -> data.getAsJsonObject().get("groupId").getAsInt());
    }

    public CompletableFuture<Void> deleteGroup(int groupId) {
        return call(Command.DELETE_GROUP, group(groupId), data -> null);
    }

    public CompletableFuture<Void> leaveGroup(int groupId) {
        return call(Command.LEAVE_GROUP, group(groupId), data -> null);
    }

    public CompletableFuture<Void> addMember(int groupId, String email) {
        JsonObject payload = group(groupId);
        payload.addProperty("email", email);
        return call(Command.ADD_MEMBER, payload, data -> null);
    }

    public CompletableFuture<Void> removeMember(int groupId, String email) {
        JsonObject payload = group(groupId);
        payload.addProperty("email", email);
        return call(Command.REMOVE_MEMBER, payload, data -> null);
    }

    // ------------------------------------------------------------------ nội bộ

    /**
     * Lệnh chỉ ĐỌC hoặc lặp lại vô hại (idempotent): gửi lại sau khi tự kết nối
     * lại là an toàn. Lệnh thay đổi dữ liệu không nằm đây (VD SEND_MAIL gửi lại có
     * thể tạo hai thư nếu Server đã xử lý lần đầu nhưng phản hồi bị mất).
     */
    private static final Set<Command> RETRY_SAFE = EnumSet.of(Command.GET_FOLDERS, Command.GET_MAIL_LIST,
            Command.GET_MAIL_DETAIL, Command.GET_THREAD, Command.SEARCH_MAIL, Command.SUGGEST_CONTACTS,
            Command.GET_DRAFTS, Command.GET_MY_GROUPS, Command.GET_SCHEDULED, Command.GET_SETTINGS,
            Command.DOWNLOAD_ATTACHMENT, Command.UPLOAD_STATUS, Command.MARK_READ, Command.SET_STARRED);

    private <T> CompletableFuture<T> call(Command command, JsonObject payload, Function<JsonElement, T> parser) {
        CompletableFuture<ResponseMessage> sent = connection.sendRequest(command, payload);
        if (RETRY_SAFE.contains(command)) {
            sent = sent.exceptionallyCompose(error -> {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (!(cause instanceof ConnectionLostException) || !Reconnector.get().isActive()) {
                    return CompletableFuture.failedFuture(cause);
                }
                // Đợi Reconnector kết nối + đăng nhập lại rồi gửi lại MỘT lần.
                return Reconnector.get().awaitReconnected().thenCompose(ignored -> connection.sendRequest(command, payload));
            });
        }
        return sent.thenApply(response -> {
            if (!response.isOk()) {
                throw new CompletionException(new ApiException(response.getCode(), response.getMessage() == null
                        || response.getMessage().isBlank() ? "Yêu cầu không thành công." : response.getMessage()));
            }
            try {
                return parser.apply(response.getData());
            } catch (RuntimeException error) {
                throw new CompletionException(new ApiException("Phản hồi của máy chủ không hợp lệ."));
            }
        });
    }

    private static List<MailSummary> summaries(JsonElement data) {
        return list(data, new TypeToken<List<MailSummary>>() { }.getType());
    }

    private static <T> T as(JsonElement data, Class<T> type) {
        return ProtocolUtil.getGson().fromJson(data, type);
    }

    private static <T> List<T> list(JsonElement data, Type type) {
        return ProtocolUtil.getGson().fromJson(data, type);
    }

    private static int processed(JsonElement data) {
        return data != null && data.isJsonObject() && data.getAsJsonObject().has("processed")
                ? data.getAsJsonObject().get("processed").getAsInt()
                : 0;
    }

    private static JsonObject entries(Collection<Integer> entryIds) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        entryIds.forEach(ids::add);
        payload.add("entryIds", ids);
        return payload;
    }

    private static JsonObject group(int groupId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("groupId", groupId);
        return payload;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonObject object(String... keyValues) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            object.addProperty(keyValues[i], keyValues[i + 1] == null ? "" : keyValues[i + 1]);
        }
        return object;
    }
}
