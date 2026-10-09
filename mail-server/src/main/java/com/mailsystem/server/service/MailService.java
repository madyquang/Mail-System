package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.GroupDAO;
import com.mailsystem.server.dao.JdbcMailDAO;
import com.mailsystem.server.dao.JdbcAccountDAO;
import com.mailsystem.server.dao.JdbcFolderDAO;
import com.mailsystem.server.dao.JdbcGroupDAO;
import com.mailsystem.server.dao.MailDAO;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.ServerConfig;
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.transfer.UploadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;

/**
 * Nghiệp vụ thư: danh sách, chi tiết, gửi thư, tệp đính kèm, đã đọc, thùng rác,
 * tìm kiếm. Sau mỗi thay đổi trạng thái, Service đẩy EVENT qua SessionManager
 * để các phiên đang online cập nhật ngay (server push, không polling).
 *
 * Tệp đính kèm đi theo hai pha:
 * 1. UPLOAD_ATTACHMENT_CHUNK: Client gửi từng khối, Server ghi vào tệp tạm
 *    của kết nối (UploadManager) và trả uploadId.
 * 2. SEND_MAIL: Client chỉ gửi danh sách uploadId; Server kiểm tra lại số
 *    lượng/dung lượng/định dạng rồi chuyển tệp tạm vào kho cùng transaction
 *    lưu thư (all-or-nothing).
 */
public class MailService {

    /** Số thư tối đa trong một thao tác hàng loạt (giới hạn kích thước request). */
    static final int MAX_ENTRIES_PER_REQUEST = 500;

    private final MailDAO mailDAO;
    private final AccountDAO accountDAO;
    private final FolderDAO folderDAO;
    private final GroupDAO groupDAO;
    private final Path attachmentDirectory;

    public MailService() {
        this(new JdbcMailDAO(), new JdbcAccountDAO(), new JdbcFolderDAO(), new JdbcGroupDAO(),
                ServerConfig.attachmentsDir());
    }

    MailService(MailDAO mailDAO, AccountDAO accountDAO, FolderDAO folderDAO, GroupDAO groupDAO,
            Path attachmentDirectory) {
        this.mailDAO = mailDAO;
        this.accountDAO = accountDAO;
        this.folderDAO = folderDAO;
        this.groupDAO = groupDAO;
        this.attachmentDirectory = attachmentDirectory;
    }

    public ResponseMessage getMailList(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer folderId = readPositiveInt(payload, "folderId");
        if (folderId == null) {
            return ResponseMessage.error(requestId, "Thư mục không hợp lệ.");
        }
        try {
            return ResponseMessage.ok(requestId,
                    ProtocolUtil.getGson().toJsonTree(mailDAO.getMailList(accountId, folderId)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tải danh sách thư.");
        }
    }

    public ResponseMessage getMailDetail(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer mailId = readPositiveInt(payload, "mailId");
        if (mailId == null) {
            return ResponseMessage.error(requestId, "Thư không hợp lệ.");
        }
        Integer entryId = null;
        if (payload.getAsJsonObject().has("entryId")) {
            entryId = readPositiveInt(payload, "entryId");
            if (entryId == null) {
                return ResponseMessage.error(requestId, "Thư không hợp lệ.");
            }
        }
        try {
            Optional<MailDetail> detail = mailDAO.getMailDetail(mailId, accountId, entryId);
            return detail.<ResponseMessage>map(mail -> ResponseMessage.ok(requestId,
                    ProtocolUtil.getGson().toJsonTree(mail)))
                    .orElseGet(() -> ResponseMessage.error(requestId, "Không tìm thấy thư."));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tải nội dung thư.");
        }
    }

    public ResponseMessage downloadAttachment(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        Integer attachmentId = readPositiveInt(payload, "attachmentId");
        if (attachmentId == null) {
            return ResponseMessage.error(requestId, "Tệp đính kèm không hợp lệ.");
        }
        Long offset = readNonNegativeLong(payload, "offset");
        if (offset == null) {
            return ResponseMessage.error(requestId, "Vị trí tải tệp không hợp lệ.");
        }
        try {
            Optional<com.mailsystem.common.model.Attachment> attachment = mailDAO.getAttachmentChunk(
                    attachmentId, accountId, offset, ProtocolConstants.CHUNK_SIZE_BYTES);
            return attachment.<ResponseMessage>map(file -> ResponseMessage.ok(requestId,
                    ProtocolUtil.getGson().toJsonTree(file)))
                    .orElseGet(() -> ResponseMessage.error(requestId,
                            "Không tìm thấy tệp hoặc bạn không có quyền truy cập."));
        } catch (SQLException error) {
            System.err.println("[MailService] Khong tai duoc attachmentId=" + attachmentId
                    + ", offset=" + offset + ": " + error.getMessage());
            return ResponseMessage.error(requestId, "Không thể tải nội dung tệp đính kèm.");
        }
    }

    private Long readNonNegativeLong(JsonElement payload, String property) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonElement value = payload.getAsJsonObject().get(property);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            long number = value.getAsLong();
            return number >= 0 ? number : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /**
     * Nhận 1 khối tệp đính kèm. Payload: {uploadId?, filename, sizeBytes,
     * offset, dataBase64}. Khối đầu tiên không có uploadId; Server tạo phiên
     * tải lên và trả uploadId để các khối sau tham chiếu.
     */
    public ResponseMessage uploadAttachmentChunk(String requestId, Integer accountId, JsonElement payload,
            UploadManager uploads) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thông tin tải tệp lên không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        Long offset = readNonNegativeLong(payload, "offset");
        Long sizeBytes = readNonNegativeLong(payload, "sizeBytes");
        if (offset == null || sizeBytes == null) {
            return ResponseMessage.error(requestId, "Thiếu offset hoặc kích thước tệp.");
        }
        String dataBase64 = readString(values, "dataBase64");
        if (dataBase64.length() > 4L * ((ProtocolConstants.CHUNK_SIZE_BYTES + 2) / 3)) {
            return ResponseMessage.error(requestId, "Khối dữ liệu vượt quá giới hạn.");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(dataBase64);
        } catch (IllegalArgumentException error) {
            return ResponseMessage.error(requestId, "Khối dữ liệu không phải Base64 hợp lệ.");
        }
        try {
            UploadManager.ChunkResult result = uploads.acceptChunk(readString(values, "uploadId"),
                    readString(values, "filename"), sizeBytes, offset, data);
            JsonObject response = new JsonObject();
            response.addProperty("uploadId", result.uploadId());
            response.addProperty("receivedBytes", result.receivedBytes());
            response.addProperty("complete", result.complete());
            return ResponseMessage.ok(requestId, response);
        } catch (UploadManager.UploadException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        } catch (IOException error) {
            System.err.println("[MailService] Khong ghi duoc khoi tai len: " + error.getMessage());
            return ResponseMessage.error(requestId, "Máy chủ không lưu được tệp tải lên.");
        }
    }

    public ResponseMessage sendMail(String requestId, Integer accountId, JsonElement payload,
            UploadManager uploads) {
        // Tệp tạm đã lấy ra khỏi UploadManager thuộc về lần gửi này: thành
        // công thì đã được chuyển vào kho, thất bại thì phải xoá tại đây.
        List<Path> claimedUploads = new ArrayList<>();
        try {
            return sendMail(requestId, accountId, payload, uploads, claimedUploads);
        } finally {
            deleteStoredFiles(claimedUploads);
        }
    }

    private ResponseMessage sendMail(String requestId, Integer accountId, JsonElement payload,
            UploadManager uploads, List<Path> claimedUploads) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thông tin thư gửi không hợp lệ.");
        }

        JsonObject values = payload.getAsJsonObject();
        String subject = readString(values, "subject").trim();
        String body = readString(values, "body");
        if (subject.isBlank()) {
            subject = "(Không có tiêu đề)";
        }
        if (subject.length() > 255) {
            return ResponseMessage.error(requestId, "Tiêu đề không được vượt quá 255 ký tự.");
        }

        Map<Integer, Recipient> recipients;
        List<PreparedAttachment> attachments;
        try {
            recipients = resolveRecipients(values, accountId);
            if (recipients.isEmpty()) {
                return ResponseMessage.error(requestId, "Thêm ít nhất một người nhận hợp lệ.");
            }
            attachments = parseAttachments(values, uploads, claimedUploads);
        } catch (InvalidRecipientException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        } catch (IllegalArgumentException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể kiểm tra người nhận trong cơ sở dữ liệu.");
        }

        Optional<Integer> sentFolder;
        Map<Integer, Integer> inboxFolders = new LinkedHashMap<>();
        try {
            sentFolder = folderDAO.findFolderIdByType(accountId, "SENT");
            for (int recipientId : recipients.keySet()) {
                Optional<Integer> inbox = folderDAO.findFolderIdByType(recipientId, "INBOX");
                if (inbox.isEmpty()) {
                    return ResponseMessage.error(requestId, "Không tìm thấy hộp thư của một người nhận.");
                }
                inboxFolders.put(recipientId, inbox.get());
            }
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể truy cập thư mục người dùng.");
        }
        if (sentFolder.isEmpty()) {
            return ResponseMessage.error(requestId, "Tài khoản gửi chưa có thư mục Đã gửi.");
        }

        List<Path> storedFiles = new ArrayList<>();
        int mailId;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                mailId = mailDAO.insertMail(connection, accountId, subject, body);
                mailDAO.insertMailRecipient(connection, mailId, accountId, sentFolder.get(), "SENDER", true);
                for (Recipient recipient : recipients.values()) {
                    mailDAO.insertMailRecipient(connection, mailId, recipient.accountId,
                            inboxFolders.get(recipient.accountId), recipient.type, false);
                }

                if (!attachments.isEmpty()) {
                    Files.createDirectories(attachmentDirectory);
                    for (PreparedAttachment attachment : attachments) {
                        Path storedFile = attachmentDirectory.resolve(UUID.randomUUID() + "." + attachment.extension);
                        if (attachment.uploadedFile != null) {
                            Files.move(attachment.uploadedFile, storedFile, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.write(storedFile, attachment.content);
                        }
                        storedFiles.add(storedFile);
                        mailDAO.insertAttachment(connection, mailId, attachment.filename, attachment.mimeType,
                                attachment.sizeBytes, storedFile.toString());
                    }
                }

                connection.commit();
            } catch (SQLException | IOException error) {
                rollback(connection, error);
                deleteStoredFiles(storedFiles);
                System.err.println("[MailService] sendMail failed: " + error.getMessage());
                return ResponseMessage.error(requestId, "Không thể lưu thư; chưa có dữ liệu nào được ghi.");
            }
        } catch (SQLException error) {
            deleteStoredFiles(storedFiles);
            return ResponseMessage.error(requestId, "Không thể kết nối cơ sở dữ liệu.");
        }

        JsonObject eventData = new JsonObject();
        eventData.addProperty("mailId", mailId);
        EventMessage event = new EventMessage(EventName.NEW_MAIL, eventData);
        Set<Integer> notifyAccounts = new LinkedHashSet<>(recipients.keySet());
        notifyAccounts.add(accountId);
        for (int notifyAccount : notifyAccounts) {
            SessionManager.getInstance().pushEvent(notifyAccount, event);
        }

        JsonObject data = new JsonObject();
        data.addProperty("mailId", mailId);
        return ResponseMessage.ok(requestId, data);
    }

    // ---- Thao tác trên một hoặc nhiều thư (payload: {entryId} hoặc {entryIds: [...]}) ----

    public ResponseMessage markRead(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::markRead, EventName.MAIL_READ_UPDATED,
                "Thư cần đánh dấu đã đọc không hợp lệ.", "Không tìm thấy thư trong hộp thư của bạn.",
                "Không thể cập nhật trạng thái đã đọc của thư.");
    }

    public ResponseMessage deleteMail(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::moveMailToTrash, EventName.MAIL_DELETED,
                "Thư cần xóa không hợp lệ.", "Không tìm thấy thư trong hộp thư của bạn.",
                "Không thể chuyển thư vào Thùng rác.");
    }

    public ResponseMessage deleteTrashMail(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::deleteTrashMail, EventName.MAIL_DELETED,
                "Thư cần xóa không hợp lệ.", "Không tìm thấy thư trong Thùng rác của bạn.",
                "Không thể xóa vĩnh viễn thư trong Thùng rác.");
    }

    public ResponseMessage restoreTrashMail(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::restoreTrashMail, EventName.MAIL_RESTORED,
                "Thư cần khôi phục không hợp lệ.", "Không tìm thấy thư trong Thùng rác của bạn.",
                "Không thể khôi phục thư từ Thùng rác.");
    }

    @FunctionalInterface
    private interface EntryOperation {
        boolean apply(int entryId, int accountId) throws SQLException;
    }

    /**
     * Áp dụng một thao tác cho nhiều bản thư trong MỘT request: Client chọn
     * nhiều thư chỉ tốn một vòng gửi/nhận (round trip) thay vì N vòng, và các
     * phiên khác chỉ nhận MỘT EVENT thay vì N lần tải lại danh sách.
     * Mỗi bản thư được kiểm tra quyền riêng; bản không thuộc tài khoản bị bỏ qua.
     */
    private ResponseMessage applyToEntries(String requestId, Integer accountId, JsonElement payload,
            EntryOperation operation, EventName eventName, String invalidMessage, String notFoundMessage,
            String failureMessage) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        List<Integer> entryIds = readEntryIds(payload);
        if (entryIds == null) {
            return ResponseMessage.error(requestId, invalidMessage);
        }

        List<Integer> processed = new ArrayList<>();
        SQLException failure = null;
        for (int entryId : entryIds) {
            try {
                if (operation.apply(entryId, accountId)) {
                    processed.add(entryId);
                }
            } catch (SQLException error) {
                failure = error;
                System.err.println("[MailService] " + eventName + " failed for accountId=" + accountId
                        + ", entryId=" + entryId + ": " + error.getMessage());
                break;
            }
        }

        if (!processed.isEmpty()) {
            JsonObject eventData = new JsonObject();
            eventData.addProperty("entryId", processed.get(0));
            eventData.add("entryIds", ProtocolUtil.getGson().toJsonTree(processed));
            SessionManager.getInstance().pushEvent(accountId, new EventMessage(eventName, eventData));
        }
        if (failure != null) {
            return ResponseMessage.error(requestId, failureMessage
                    + (processed.isEmpty() ? "" : " Đã xử lý " + processed.size() + "/" + entryIds.size() + " thư."));
        }
        if (processed.isEmpty()) {
            return ResponseMessage.error(requestId, notFoundMessage);
        }
        JsonObject data = new JsonObject();
        data.addProperty("processed", processed.size());
        data.addProperty("requested", entryIds.size());
        return ResponseMessage.ok(requestId, data);
    }

    /** Đọc {entryId} hoặc {entryIds: [...]}; trả null nếu không hợp lệ. */
    private List<Integer> readEntryIds(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject values = payload.getAsJsonObject();
        if (!values.has("entryIds")) {
            Integer single = readPositiveInt(payload, "entryId");
            return single == null ? null : List.of(single);
        }
        if (!values.get("entryIds").isJsonArray()) {
            return null;
        }
        JsonArray array = values.getAsJsonArray("entryIds");
        if (array.isEmpty() || array.size() > MAX_ENTRIES_PER_REQUEST) {
            return null;
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (JsonElement element : array) {
            try {
                int id = element.getAsInt();
                if (id <= 0) {
                    return null;
                }
                ids.add(id);
            } catch (RuntimeException error) {
                return null;
            }
        }
        return new ArrayList<>(ids);
    }

    public ResponseMessage emptyTrash(String requestId, Integer accountId) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        try {
            int deletedCount = mailDAO.emptyTrash(accountId);
            if (deletedCount > 0) {
                SessionManager.getInstance().pushEvent(accountId,
                        new EventMessage(EventName.MAIL_DELETED, new JsonObject()));
            }
            return ResponseMessage.ok(requestId, new JsonPrimitive(deletedCount));
        } catch (SQLException error) {
            System.err.println("[MailService] emptyTrash failed for accountId=" + accountId
                    + ": " + error.getMessage());
            return ResponseMessage.error(requestId, "Không thể xóa thư trong Thùng rác.");
        }
    }

    public ResponseMessage searchMail(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Điều kiện tìm kiếm không hợp lệ.");
        }

        JsonObject values = payload.getAsJsonObject();
        if (values.has("keyword") && (!values.get("keyword").isJsonPrimitive()
                || !values.get("keyword").getAsJsonPrimitive().isString())) {
            return ResponseMessage.error(requestId, "Từ khóa tìm kiếm không hợp lệ.");
        }
        if (values.has("fromFilter") && (!values.get("fromFilter").isJsonPrimitive()
                || !values.get("fromFilter").getAsJsonPrimitive().isString())) {
            return ResponseMessage.error(requestId, "Bộ lọc người gửi không hợp lệ.");
        }
        String keyword = values.has("keyword") ? values.get("keyword").getAsString().trim() : "";
        String fromFilter = values.has("fromFilter") ? values.get("fromFilter").getAsString().trim() : "";
        if ((keyword.isBlank() && fromFilter.isBlank()) || keyword.length() > 255 || fromFilter.length() > 255) {
            return ResponseMessage.error(requestId,
                    "Nhập từ khóa hoặc người gửi; mỗi điều kiện tối đa 255 ký tự.");
        }

        Integer folderId = null;
        if (values.has("folderId")) {
            folderId = readPositiveInt(payload, "folderId");
            if (folderId == null) {
                return ResponseMessage.error(requestId, "Thư mục không hợp lệ.");
            }
        }
        try {
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson()
                    .toJsonTree(mailDAO.searchMail(accountId, folderId, keyword, fromFilter)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tìm kiếm thư.");
        }
    }

    private Integer readPositiveInt(JsonElement payload, String property) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject values = payload.getAsJsonObject();
        if (!values.has(property) || !values.get(property).isJsonPrimitive()) {
            return null;
        }
        try {
            int value = values.get(property).getAsInt();
            return value > 0 ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private Map<Integer, Recipient> resolveRecipients(JsonObject payload, int senderId)
            throws SQLException, InvalidRecipientException {
        Map<Integer, Recipient> recipients = new LinkedHashMap<>();
        resolveRecipientList(payload, "to", "TO", recipients, senderId);
        resolveRecipientList(payload, "cc", "CC", recipients, senderId);
        resolveRecipientList(payload, "bcc", "BCC", recipients, senderId);
        return recipients;
    }

    private void resolveRecipientList(JsonObject payload, String field, String type,
            Map<Integer, Recipient> recipients, int senderId) throws SQLException, InvalidRecipientException {
        if (!payload.has(field)) {
            return;
        }
        JsonElement value = payload.get(field);
        if (!value.isJsonArray()) {
            throw new InvalidRecipientException("Danh sách " + field.toUpperCase(Locale.ROOT) + " không hợp lệ.");
        }
        JsonArray addresses = value.getAsJsonArray();
        for (JsonElement addressElement : addresses) {
            if (!addressElement.isJsonPrimitive() || !addressElement.getAsJsonPrimitive().isString()) {
                throw new InvalidRecipientException("Người nhận không hợp lệ.");
            }
            String recipient = addressElement.getAsString().trim();
            if (recipient.isBlank()) {
                continue;
            }
            if (recipient.contains("@")) {
                String email = recipient.toLowerCase(Locale.ROOT);
                if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                    throw new InvalidRecipientException("Địa chỉ email không hợp lệ: " + recipient);
                }
                Optional<Account> account = accountDAO.findByEmail(email);
                if (account.isEmpty()) {
                    throw new InvalidRecipientException("Người nhận không tồn tại: " + recipient);
                }
                recipients.putIfAbsent(account.get().getAccountId(),
                        new Recipient(account.get().getAccountId(), type));
            } else {
                Optional<Integer> groupId = groupDAO.findGroupIdByName(recipient);
                if (groupId.isEmpty()) {
                    throw new InvalidRecipientException("Người nhận hoặc nhóm không tồn tại: " + recipient);
                }
                if (!groupDAO.isMember(groupId.get(), senderId)) {
                    throw new InvalidRecipientException("Bạn không phải thành viên của nhóm: " + recipient);
                }
                // Người gửi là thành viên nhóm nhưng không nhận lại bản thư của chính mình
                // (thư đã nằm trong Đã gửi), giống cách mailing list thông thường hoạt động.
                int otherMembers = 0;
                for (int memberId : groupDAO.getMemberAccountIds(groupId.get())) {
                    if (memberId == senderId) {
                        continue;
                    }
                    recipients.putIfAbsent(memberId, new Recipient(memberId, type));
                    otherMembers++;
                }
                if (otherMembers == 0) {
                    throw new InvalidRecipientException("Nhóm " + recipient + " chưa có thành viên nào khác ngoài bạn.");
                }
            }
        }
    }

    /**
     * Chuẩn bị danh sách tệp đính kèm. Mỗi phần tử là {uploadId} (tệp đã tải
     * lên theo khối) hoặc dạng cũ {filename, sizeBytes, dataBase64} cho tệp
     * nhỏ nằm gọn trong một frame (VD: khi thử bằng ManualTestClient).
     */
    private List<PreparedAttachment> parseAttachments(JsonObject payload, UploadManager uploads,
            List<Path> claimedUploads) {
        List<PreparedAttachment> attachments = new ArrayList<>();
        if (!payload.has("attachments") || payload.get("attachments").isJsonNull()) {
            return attachments;
        }
        if (!payload.get("attachments").isJsonArray()) {
            throw new IllegalArgumentException("Danh sách tệp đính kèm không hợp lệ.");
        }

        long totalBytes = 0;
        JsonArray values = payload.getAsJsonArray("attachments");
        if (values.size() > ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
            throw new IllegalArgumentException("Mỗi thư chỉ được đính kèm tối đa "
                    + ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL + " tệp.");
        }
        for (JsonElement item : values) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("Thông tin tệp đính kèm không hợp lệ.");
            }
            JsonObject attachment = item.getAsJsonObject();
            PreparedAttachment prepared = attachment.has("uploadId")
                    ? prepareUploaded(readString(attachment, "uploadId"), uploads, claimedUploads)
                    : prepareInline(attachment);
            if (prepared.sizeBytes > ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES - totalBytes) {
                throw new IllegalArgumentException("Tổng dung lượng đính kèm không được vượt quá 25 MB.");
            }
            totalBytes += prepared.sizeBytes;
            attachments.add(prepared);
        }
        return attachments;
    }

    private PreparedAttachment prepareUploaded(String uploadId, UploadManager uploads, List<Path> claimedUploads) {
        if (uploads == null) {
            throw new IllegalArgumentException("Tệp đính kèm chưa được tải lên.");
        }
        UploadManager.PendingUpload upload;
        try {
            upload = uploads.take(uploadId);
        } catch (UploadManager.UploadException error) {
            throw new IllegalArgumentException(error.getMessage());
        }
        claimedUploads.add(upload.file());
        long actualSize;
        try {
            actualSize = Files.size(upload.file());
        } catch (IOException error) {
            throw new IllegalArgumentException("Không đọc được tệp đã tải lên: " + upload.filename());
        }
        if (actualSize != upload.declaredSize()) {
            throw new IllegalArgumentException("Kích thước tệp không khớp nội dung: " + upload.filename());
        }
        return new PreparedAttachment(upload.filename(), mimeType(upload.extension()), upload.extension(),
                actualSize, null, upload.file());
    }

    private PreparedAttachment prepareInline(JsonObject attachment) {
        String filename = readString(attachment, "filename").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).trim();
        String dataBase64 = readString(attachment, "dataBase64");
        if (filename.isBlank() || filename.length() > 255 || dataBase64.isBlank()) {
            throw new IllegalArgumentException("Tên hoặc nội dung tệp đính kèm không hợp lệ.");
        }
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ProtocolConstants.ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Không hỗ trợ định dạng tệp: " + filename);
        }

        byte[] content;
        try {
            content = Base64.getDecoder().decode(dataBase64);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Nội dung tệp không hợp lệ: " + filename);
        }
        if (!attachment.has("sizeBytes") || !attachment.get("sizeBytes").isJsonPrimitive()) {
            throw new IllegalArgumentException("Thiếu kích thước tệp: " + filename);
        }
        long declaredSize;
        try {
            declaredSize = attachment.get("sizeBytes").getAsLong();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Kích thước tệp không hợp lệ: " + filename);
        }
        if (declaredSize != content.length) {
            throw new IllegalArgumentException("Kích thước tệp không khớp nội dung: " + filename);
        }
        return new PreparedAttachment(filename, mimeType(extension), extension, content.length, content, null);
    }

    private String mimeType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    private String readString(JsonObject object, String property) {
        if (!object.has(property) || !object.get(property).isJsonPrimitive()
                || !object.get(property).getAsJsonPrimitive().isString()) {
            return "";
        }
        return object.get(property).getAsString();
    }

    private void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }

    private void deleteStoredFiles(List<Path> storedFiles) {
        for (Path storedFile : storedFiles) {
            try {
                Files.deleteIfExists(storedFile);
            } catch (IOException ignored) {
            }
        }
    }

    private record Recipient(int accountId, String type) {
    }

    /** Đúng một trong hai nguồn có giá trị: content (dạng cũ) hoặc uploadedFile. */
    private record PreparedAttachment(String filename, String mimeType, String extension, long sizeBytes,
            byte[] content, Path uploadedFile) {
    }

    private static class InvalidRecipientException extends Exception {
        InvalidRecipientException(String message) {
            super(message);
        }
    }
}
