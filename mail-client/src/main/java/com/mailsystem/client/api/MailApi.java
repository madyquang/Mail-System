package com.mailsystem.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.model.Contact;
import com.mailsystem.common.model.Draft;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailGroupDTO;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ProtocolUtil;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
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

    public CompletableFuture<Account> login(String email, String password) {
        return call(Command.LOGIN, object("email", email, "password", password), data -> as(data, Account.class));
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

    public CompletableFuture<Integer> changePassword(String currentPassword, String newPassword) {
        return call(Command.CHANGE_PASSWORD, object("currentPassword", currentPassword, "newPassword", newPassword),
                data -> data.getAsJsonObject().get("revokedSessions").getAsInt());
    }

    // ------------------------------------------------------------------ thư

    public CompletableFuture<List<Folder>> folders() {
        return call(Command.GET_FOLDERS, null, data -> list(data, new TypeToken<List<Folder>>() { }.getType()));
    }

    public CompletableFuture<List<MailSummary>> mailList(int folderId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("folderId", folderId);
        return call(Command.GET_MAIL_LIST, payload, MailApi::summaries);
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
    public record OutgoingMail(List<String> to, List<String> cc, List<String> bcc, String subject, String body,
            List<String> uploadIds, List<Integer> forwardAttachmentIds, Integer draftId) {
    }

    public CompletableFuture<Integer> send(OutgoingMail mail) {
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
        return call(Command.SEND_MAIL, payload, data -> data.getAsJsonObject().get("mailId").getAsInt());
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

    public CompletableFuture<Integer> createGroup(String name) {
        return call(Command.CREATE_GROUP, object("groupName", name), data -> data.getAsJsonObject().get("groupId").getAsInt());
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

    private <T> CompletableFuture<T> call(Command command, JsonObject payload, Function<JsonElement, T> parser) {
        return connection.sendRequest(command, payload).thenApply(response -> {
            if (!response.isOk()) {
                throw new CompletionException(new ApiException(response.getMessage() == null
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
