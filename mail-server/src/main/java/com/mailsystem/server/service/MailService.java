package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.model.MailDetail;
import com.mailsystem.common.model.MailSummary;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.DraftDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.GroupDAO;
import com.mailsystem.server.dao.MailDAO;
import com.mailsystem.server.dao.OutboxDAO;
import com.mailsystem.server.dao.jdbc.JdbcOutboxDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcDraftDAO;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;
import com.mailsystem.server.dao.jdbc.JdbcGroupDAO;
import com.mailsystem.server.dao.jdbc.JdbcMailDAO;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.security.FileSignatures;
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.storage.AttachmentStorage;
import com.mailsystem.server.transfer.UploadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    static final int MAX_ENTRIES_PER_REQUEST = ProtocolConstants.MAX_ENTRIES_PER_REQUEST;

    private final MailDAO mailDAO;
    private final AccountDAO accountDAO;
    private final FolderDAO folderDAO;
    private final GroupDAO groupDAO;
    private final DraftDAO draftDAO;
    private final OutboxDAO outboxDAO;
    private final Path attachmentDirectory;

    public MailService() {
        this(new JdbcMailDAO(), new JdbcAccountDAO(), new JdbcFolderDAO(), new JdbcGroupDAO(), new JdbcDraftDAO(),
                new JdbcOutboxDAO(), AttachmentStorage.directory());
    }

    MailService(MailDAO mailDAO, AccountDAO accountDAO, FolderDAO folderDAO, GroupDAO groupDAO,
            Path attachmentDirectory) {
        this(mailDAO, accountDAO, folderDAO, groupDAO, null, attachmentDirectory);
    }

    MailService(MailDAO mailDAO, AccountDAO accountDAO, FolderDAO folderDAO, GroupDAO groupDAO, DraftDAO draftDAO,
            Path attachmentDirectory) {
        this(mailDAO, accountDAO, folderDAO, groupDAO, draftDAO, null, attachmentDirectory);
    }

    MailService(MailDAO mailDAO, AccountDAO accountDAO, FolderDAO folderDAO, GroupDAO groupDAO, DraftDAO draftDAO,
            OutboxDAO outboxDAO, Path attachmentDirectory) {
        this.outboxDAO = outboxDAO;
        this.mailDAO = mailDAO;
        this.accountDAO = accountDAO;
        this.folderDAO = folderDAO;
        this.groupDAO = groupDAO;
        this.draftDAO = draftDAO;
        this.attachmentDirectory = attachmentDirectory;
    }

    /** Payload {folderId} hoặc {view: "STARRED"} (thư gắn sao ở mọi thư mục trừ Thùng rác). */
    public ResponseMessage getMailList(String requestId, Integer accountId, JsonElement payload) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Bạn chưa đăng nhập.");
        }
        boolean starredView = payload != null && payload.isJsonObject()
                && "STARRED".equals(readString(payload.getAsJsonObject(), "view"));
        Integer folderId = starredView ? null : readPositiveInt(payload, "folderId");
        if (!starredView && folderId == null) {
            return ResponseMessage.error(requestId, "Thư mục không hợp lệ.");
        }
        boolean threaded = readBoolean(payload, "threaded", false);
        try {
            List<MailSummary> mails = starredView ? mailDAO.getStarredList(accountId) : mailDAO.getMailList(accountId, folderId);
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(threaded ? groupByThread(mails) : mails));
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
            detail.ifPresent(mail -> notifyReadReceipt(mail, accountId));
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
        if (subject.length() > ProtocolConstants.MAX_SUBJECT_CHARS) {
            return ResponseMessage.error(requestId, "Tiêu đề không được vượt quá 255 ký tự.");
        }
        if (body.length() > ProtocolConstants.MAX_BODY_CHARS) {
            return ResponseMessage.error(requestId, "Nội dung thư không được vượt quá "
                    + ProtocolConstants.MAX_BODY_CHARS + " ký tự.");
        }
        Integer draftId = values.has("draftId") ? readPositiveInt(payload, "draftId") : null;
        Integer inReplyTo = values.has("inReplyTo") ? readPositiveInt(payload, "inReplyTo") : null;

        // Thời điểm gửi: ngay, sau vài giây (hoàn tác được) hoặc hẹn giờ.
        LocalDateTime sendAt;
        try {
            sendAt = parseSendAt(values);
        } catch (IllegalArgumentException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        }
        if (sendAt != null && outboxDAO == null) {
            return ResponseMessage.error(requestId, "Máy chủ chưa hỗ trợ gửi hẹn giờ.");
        }

        Map<Integer, Recipient> recipients;
        List<PreparedAttachment> attachments;
        Integer threadId = null;
        try {
            recipients = resolveRecipients(values, accountId);
            if (recipients.isEmpty()) {
                return ResponseMessage.error(requestId, "Thêm ít nhất một người nhận hợp lệ.");
            }
            attachments = parseAttachments(values, uploads, claimedUploads);
            attachments.addAll(parseForwardedAttachments(values, accountId,
                    attachments.stream().mapToLong(PreparedAttachment::sizeBytes).sum(), attachments.size()));
            if (inReplyTo != null) {
                // Chỉ nối vào hội thoại của thư mà người gửi có quyền xem.
                threadId = mailDAO.findThreadRoot(inReplyTo, accountId).orElse(null);
                if (threadId == null) {
                    inReplyTo = null;
                }
            }
        } catch (InvalidRecipientException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        } catch (IllegalArgumentException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể kiểm tra người nhận trong cơ sở dữ liệu.");
        }

        List<Path> storedFiles = new ArrayList<>();
        List<OutboxDAO.StoredFile> files;
        try {
            files = materialize(attachments, storedFiles);
        } catch (IOException error) {
            deleteStoredFiles(storedFiles);
            return ResponseMessage.error(requestId, "Máy chủ không lưu được tệp đính kèm.");
        }

        if (sendAt != null) {
            JsonObject stored = new JsonObject();
            for (String field : new String[] {"to", "cc", "bcc"}) {
                stored.add(field, values.has(field) && values.get(field).isJsonArray()
                        ? values.get(field) : new JsonArray());
            }
            stored.addProperty("subject", subject);
            stored.addProperty("body", body);
            if (inReplyTo != null) {
                stored.addProperty("inReplyTo", inReplyTo);
            }
            if (draftId != null) {
                stored.addProperty("draftId", draftId);
            }
            try {
                int outboxId = outboxDAO.insert(accountId, ProtocolUtil.toJson(stored), sendAt, files);
                SessionManager.getInstance().pushEvent(accountId,
                        new EventMessage(EventName.SCHEDULED_UPDATED, new JsonObject()));
                JsonObject data = new JsonObject();
                data.addProperty("outboxId", outboxId);
                data.addProperty("sendAt", sendAt.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                data.addProperty("scheduled", true);
                return ResponseMessage.ok(requestId, data);
            } catch (SQLException error) {
                deleteStoredFiles(storedFiles);
                return ResponseMessage.error(requestId, "Không thể lên lịch gửi thư.");
            }
        }

        int mailId;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                mailId = deliver(connection, accountId, recipients, subject, body, files, threadId, inReplyTo);
                connection.commit();
            } catch (SQLException | IllegalStateException error) {
                rollback(connection, error instanceof Exception ? (Exception) error : new Exception(error));
                deleteStoredFiles(storedFiles);
                System.err.println("[MailService] sendMail failed: " + error.getMessage());
                return ResponseMessage.error(requestId, error instanceof IllegalStateException
                        ? error.getMessage() : "Không thể lưu thư; chưa có dữ liệu nào được ghi.");
            }
        } catch (SQLException error) {
            deleteStoredFiles(storedFiles);
            return ResponseMessage.error(requestId, "Không thể kết nối cơ sở dữ liệu.");
        }
        afterDelivery(accountId, recipients.keySet(), mailId, subject, draftId);
        JsonObject data = new JsonObject();
        data.addProperty("mailId", mailId);
        return ResponseMessage.ok(requestId, data);
    }

    /** null = gửi ngay; delaySeconds (1..30) = hoàn tác được; sendAt ISO = hẹn giờ. */
    private LocalDateTime parseSendAt(JsonObject values) {
        if (values.has("sendAt") && !values.get("sendAt").isJsonNull()) {
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(values.get("sendAt").getAsString());
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Thời gian hẹn gửi không hợp lệ.");
            }
            LocalDateTime now = LocalDateTime.now();
            if (at.isBefore(now.plusSeconds(10))) {
                throw new IllegalArgumentException("Thời gian hẹn gửi phải ở tương lai.");
            }
            if (at.isAfter(now.plusDays(ProtocolConstants.MAX_SCHEDULE_DAYS))) {
                throw new IllegalArgumentException("Chỉ hẹn gửi tối đa " + ProtocolConstants.MAX_SCHEDULE_DAYS + " ngày.");
            }
            return at;
        }
        if (values.has("delaySeconds")) {
            int delay;
            try {
                delay = values.get("delaySeconds").getAsInt();
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Thời gian chờ gửi không hợp lệ.");
            }
            if (delay <= 0) {
                return null;
            }
            return LocalDateTime.now().plusSeconds(Math.min(delay, ProtocolConstants.MAX_UNDO_SECONDS));
        }
        return null;
    }

    /** Ghi nội dung tệp vào kho (di chuyển tệp đã tải lên, sao chép tệp chuyển tiếp). */
    private List<OutboxDAO.StoredFile> materialize(List<PreparedAttachment> attachments, List<Path> storedFiles)
            throws IOException {
        List<OutboxDAO.StoredFile> files = new ArrayList<>();
        if (attachments.isEmpty()) {
            return files;
        }
        Files.createDirectories(attachmentDirectory);
        for (PreparedAttachment attachment : attachments) {
            String storageName = AttachmentStorage.newStorageName(attachment.extension);
            Path storedFile = attachmentDirectory.resolve(storageName);
            if (attachment.uploadedFile != null) {
                Files.move(attachment.uploadedFile, storedFile, StandardCopyOption.REPLACE_EXISTING);
            } else if (attachment.forwardedFile != null) {
                // Mỗi thư giữ bản sao riêng: xoá thư gốc không làm mất tệp của thư chuyển tiếp.
                Files.copy(attachment.forwardedFile, storedFile);
            } else {
                Files.write(storedFile, attachment.content);
            }
            storedFiles.add(storedFile);
            files.add(new OutboxDAO.StoredFile(attachment.filename, attachment.mimeType, attachment.sizeBytes,
                    storageName));
        }
        return files;
    }

    /**
     * Ghi thư vào CSDL trong transaction của caller: một dòng mail, một bản cho
     * người gửi (SENT), một bản cho mỗi người nhận (INBOX), các tệp đính kèm.
     * Dùng chung cho gửi ngay và cho bộ gửi thư hẹn giờ.
     */
    private int deliver(Connection connection, int senderId, Map<Integer, Recipient> recipients, String subject,
            String body, List<OutboxDAO.StoredFile> files, Integer threadId, Integer inReplyTo) throws SQLException {
        int sentFolder = folderDAO.findFolderIdByType(connection, senderId, "SENT")
                .orElseThrow(() -> new IllegalStateException("Tài khoản gửi chưa có thư mục Đã gửi."));
        Map<Integer, Integer> inboxFolders = new LinkedHashMap<>();
        for (int recipientId : recipients.keySet()) {
            inboxFolders.put(recipientId, folderDAO.findFolderIdByType(connection, recipientId, "INBOX")
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy hộp thư của một người nhận.")));
        }
        int mailId = mailDAO.insertMail(connection, senderId, subject, body, threadId, inReplyTo);
        mailDAO.insertMailRecipient(connection, mailId, senderId, sentFolder, "SENDER", true);
        for (Recipient recipient : recipients.values()) {
            mailDAO.insertMailRecipient(connection, mailId, recipient.accountId,
                    inboxFolders.get(recipient.accountId), recipient.type, false);
        }
        for (OutboxDAO.StoredFile file : files) {
            mailDAO.insertAttachment(connection, mailId, file.filename(), file.mimeType(), file.sizeBytes(),
                    file.storagePath());
        }
        return mailId;
    }

    /** Sau khi commit: xoá nháp đã gửi và đẩy NEW_MAIL tới người nhận + các phiên của người gửi. */
    private void afterDelivery(int senderId, Set<Integer> recipientIds, int mailId, String subject, Integer draftId) {
        if (draftId != null && draftDAO != null) {
            try {
                if (draftDAO.delete(senderId, List.of(draftId)) > 0) {
                    SessionManager.getInstance().pushEvent(senderId,
                            new EventMessage(EventName.DRAFTS_UPDATED, new JsonObject()));
                }
            } catch (SQLException error) {
                System.err.println("[MailService] Khong xoa duoc thu nhap " + draftId + ": " + error.getMessage());
            }
        }
        // EVENT kèm người gửi + tiêu đề để Client hiện thông báo desktop mà không cần hỏi lại.
        JsonObject eventData = new JsonObject();
        eventData.addProperty("mailId", mailId);
        eventData.addProperty("subject", subject);
        try {
            accountDAO.findById(senderId).ifPresent(sender -> {
                eventData.addProperty("senderEmail", sender.getEmail());
                eventData.addProperty("senderName", sender.getDisplayName());
            });
        } catch (SQLException error) {
            System.err.println("[MailService] Khong doc duoc thong tin nguoi gui: " + error.getMessage());
        }
        EventMessage event = new EventMessage(EventName.NEW_MAIL, eventData);
        Set<Integer> notifyAccounts = new LinkedHashSet<>(recipientIds);
        notifyAccounts.add(senderId);
        for (int notifyAccount : notifyAccounts) {
            SessionManager.getInstance().pushEvent(notifyAccount, event);
        }
    }

    // ------------------------------------------------------------------ hộp thư chờ gửi

    public ResponseMessage getScheduled(String requestId, Integer accountId) {
        try {
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(outboxDAO.listByAccount(accountId)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không tải được thư chờ gửi.");
        }
    }

    /** Huỷ thư chờ gửi; trả lại nội dung để Client mở lại khung soạn thư. */
    public ResponseMessage cancelScheduled(String requestId, Integer accountId, JsonElement payload) {
        Integer outboxId = readPositiveInt(payload, "outboxId");
        if (outboxId == null) {
            return ResponseMessage.error(requestId, "Thư chờ gửi không hợp lệ.");
        }
        Optional<OutboxDAO.Entry> entry;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                entry = outboxDAO.claim(connection, outboxId, accountId);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không huỷ được thư chờ gửi.");
        }
        if (entry.isEmpty()) {
            return ResponseMessage.error(requestId, "Thư đã được gửi hoặc không còn trong hàng chờ.");
        }
        deleteStoredNames(entry.get().files());
        SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.SCHEDULED_UPDATED, new JsonObject()));
        JsonObject data = ProtocolUtil.fromJson(entry.get().payload(), JsonObject.class);
        data.addProperty("attachmentCount", entry.get().files().size());
        return ResponseMessage.ok(requestId, data);
    }

    /** Gọi định kỳ bởi OutboxDispatcher: gửi các thư đã tới giờ. @return số thư đã xử lý */
    public int dispatchDue() {
        if (outboxDAO == null) {
            return 0;
        }
        List<Integer> due;
        try {
            due = outboxDAO.findDueIds(50);
        } catch (SQLException error) {
            System.err.println("[Outbox] Khong doc duoc hang cho: " + error.getMessage());
            return 0;
        }
        for (int outboxId : due) {
            dispatchOne(outboxId);
        }
        return due.size();
    }

    private void dispatchOne(int outboxId) {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            OutboxDAO.Entry entry;
            try {
                // Khoá + xoá dòng outbox trong CÙNG transaction với việc ghi thư: nếu người dùng
                // huỷ cùng lúc thì chỉ một bên thắng, không bao giờ vừa huỷ vừa gửi.
                Optional<OutboxDAO.Entry> claimed = outboxDAO.claim(connection, outboxId, null);
                if (claimed.isEmpty()) {
                    connection.commit();
                    return;
                }
                entry = claimed.get();
                JsonObject values = ProtocolUtil.fromJson(entry.payload(), JsonObject.class);
                Map<Integer, Recipient> recipients;
                try {
                    recipients = resolveRecipients(values, entry.accountId());
                    if (recipients.isEmpty()) {
                        throw new InvalidRecipientException("Không còn người nhận hợp lệ.");
                    }
                } catch (InvalidRecipientException error) {
                    connection.commit(); // bỏ khỏi hàng chờ, chuyển thành nháp bên dưới
                    failScheduled(entry, values, error.getMessage());
                    return;
                }
                Integer inReplyTo = values.has("inReplyTo") ? values.get("inReplyTo").getAsInt() : null;
                Integer threadId = inReplyTo == null ? null
                        : mailDAO.findThreadRoot(inReplyTo, entry.accountId()).orElse(null);
                int mailId = deliver(connection, entry.accountId(), recipients, values.get("subject").getAsString(),
                        values.get("body").getAsString(), entry.files(), threadId, threadId == null ? null : inReplyTo);
                connection.commit();
                afterDelivery(entry.accountId(), recipients.keySet(), mailId, values.get("subject").getAsString(),
                        values.has("draftId") ? values.get("draftId").getAsInt() : null);
                SessionManager.getInstance().pushEvent(entry.accountId(),
                        new EventMessage(EventName.SCHEDULED_UPDATED, new JsonObject()));
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                System.err.println("[Outbox] Gui thu cho " + outboxId + " that bai, se thu lai: " + error.getMessage());
            }
        } catch (SQLException error) {
            System.err.println("[Outbox] Khong ket noi duoc CSDL: " + error.getMessage());
        }
    }

    /** Thư hẹn giờ không gửi được (VD: người nhận đã bị xoá): chuyển thành nháp và báo người gửi. */
    private void failScheduled(OutboxDAO.Entry entry, JsonObject values, String reason) {
        deleteStoredNames(entry.files());
        if (draftDAO != null) {
            try {
                com.mailsystem.common.model.Draft draft = new com.mailsystem.common.model.Draft();
                draft.setTo(joinAddresses(values, "to"));
                draft.setCc(joinAddresses(values, "cc"));
                draft.setBcc(joinAddresses(values, "bcc"));
                draft.setSubject(values.get("subject").getAsString());
                draft.setBody(values.get("body").getAsString());
                draftDAO.insert(entry.accountId(), draft);
            } catch (SQLException error) {
                System.err.println("[Outbox] Khong luu duoc nhap: " + error.getMessage());
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("outboxId", entry.outboxId());
        data.addProperty("subject", values.get("subject").getAsString());
        data.addProperty("reason", reason);
        SessionManager.getInstance().pushEvent(entry.accountId(), new EventMessage(EventName.SCHEDULED_FAILED, data));
        SessionManager.getInstance().pushEvent(entry.accountId(), new EventMessage(EventName.DRAFTS_UPDATED, new JsonObject()));
        SessionManager.getInstance().pushEvent(entry.accountId(), new EventMessage(EventName.SCHEDULED_UPDATED, new JsonObject()));
    }

    private static String joinAddresses(JsonObject values, String field) {
        if (!values.has(field) || !values.get(field).isJsonArray()) {
            return "";
        }
        List<String> list = new ArrayList<>();
        values.getAsJsonArray(field).forEach(element -> list.add(element.getAsString()));
        return String.join(", ", list);
    }

    private void deleteStoredNames(List<OutboxDAO.StoredFile> files) {
        for (OutboxDAO.StoredFile file : files) {
            try {
                Files.deleteIfExists(AttachmentStorage.resolve(file.storagePath()));
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ hội thoại, chuyển thư mục, báo đã đọc

    /** Gộp danh sách phẳng thành mỗi hội thoại một dòng (thư mới nhất đại diện). */
    static List<MailSummary> groupByThread(List<MailSummary> mails) {
        Map<Integer, MailSummary> threads = new LinkedHashMap<>();
        Map<Integer, List<Integer>> entryIds = new LinkedHashMap<>();
        for (MailSummary mail : mails) { // đã sắp mới nhất trước
            int threadId = mail.getThreadId();
            MailSummary head = threads.get(threadId);
            entryIds.computeIfAbsent(threadId, id -> new ArrayList<>()).add(mail.getEntryId());
            if (head == null) {
                threads.put(threadId, mail);
            } else {
                head.setThreadCount(head.getThreadCount() + 1);
                head.setRead(head.isRead() && mail.isRead());
                head.setStarred(head.isStarred() || mail.isStarred());
                head.setHasAttachments(head.isHasAttachments() || mail.isHasAttachments());
            }
        }
        for (Map.Entry<Integer, MailSummary> thread : threads.entrySet()) {
            thread.getValue().setThreadEntryIds(entryIds.get(thread.getKey()));
        }
        return new ArrayList<>(threads.values());
    }

    /** Mọi thư của một hội thoại (cũ trước mới sau); mở hội thoại = đọc mọi thư trong đó. */
    public ResponseMessage getThread(String requestId, Integer accountId, JsonElement payload) {
        Integer threadId = readPositiveInt(payload, "threadId");
        if (threadId == null) {
            return ResponseMessage.error(requestId, "Hội thoại không hợp lệ.");
        }
        try {
            List<int[]> entries = mailDAO.findThreadEntries(accountId, threadId);
            if (entries.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy hội thoại.");
            }
            List<MailDetail> details = new ArrayList<>();
            for (int[] entry : entries) {
                Optional<MailDetail> detail = mailDAO.getMailDetail(entry[0], accountId, entry[1]);
                if (detail.isPresent()) {
                    notifyReadReceipt(detail.get(), accountId);
                    details.add(detail.get());
                }
            }
            return ResponseMessage.ok(requestId, ProtocolUtil.getGson().toJsonTree(details));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không tải được hội thoại.");
        }
    }

    /** Báo cho người gửi biết thư vừa được mở lần đầu (nếu người đọc cho phép). */
    private void notifyReadReceipt(MailDetail mail, int readerId) {
        if (!mail.isNewlyRead() || mail.getSenderAccountId() == readerId || mail.getSenderAccountId() == 0) {
            return;
        }
        try {
            boolean share = accountDAO.findSecuritySettings(readerId)
                    .map(AccountDAO.SecuritySettings::shareReadReceipts).orElse(false);
            if (!share) {
                return;
            }
            Optional<com.mailsystem.common.model.Account> reader = accountDAO.findById(readerId);
            JsonObject data = new JsonObject();
            data.addProperty("mailId", mail.getMailId());
            data.addProperty("subject", mail.getSubject());
            data.addProperty("readerEmail", reader.map(com.mailsystem.common.model.Account::getEmail).orElse(""));
            data.addProperty("readerName", reader.map(com.mailsystem.common.model.Account::getDisplayName).orElse(""));
            data.addProperty("readAt", LocalDateTime.now().withNano(0).toString());
            SessionManager.getInstance().pushEvent(mail.getSenderAccountId(), new EventMessage(EventName.READ_RECEIPT, data));
        } catch (SQLException error) {
            System.err.println("[MailService] Khong gui duoc bao da doc: " + error.getMessage());
        }
    }

    /** Payload {entryIds, folderId}: chuyển thư sang thư mục khác của chính mình. */
    public ResponseMessage moveMail(String requestId, Integer accountId, JsonElement payload) {
        Integer folderId = readPositiveInt(payload, "folderId");
        if (folderId == null) {
            return ResponseMessage.error(requestId, "Thư mục đích không hợp lệ.");
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("folderId", folderId);
        return applyToEntries(requestId, accountId, payload,
                (entryId, owner) -> mailDAO.moveToFolder(entryId, owner, folderId), EventName.MAIL_MOVED, extra,
                "Thư cần chuyển không hợp lệ.", "Không chuyển được thư (thư mục đích không hợp lệ hoặc thư không còn).",
                "Không thể chuyển thư.");
    }

    /** Tiến độ tải lên, để Client tải tiếp sau khi rớt mạng. */
    public ResponseMessage uploadStatus(String requestId, Integer accountId, JsonElement payload, UploadManager uploads) {
        String uploadId = payload != null && payload.isJsonObject() ? readString(payload.getAsJsonObject(), "uploadId") : "";
        try {
            UploadManager.ChunkResult status = uploads.status(uploadId);
            JsonObject data = new JsonObject();
            data.addProperty("uploadId", status.uploadId());
            data.addProperty("receivedBytes", status.receivedBytes());
            data.addProperty("complete", status.complete());
            return ResponseMessage.ok(requestId, data);
        } catch (UploadManager.UploadException error) {
            return ResponseMessage.error(requestId, error.getMessage());
        }
    }

    /** Payload {entryIds, read?}: read = false để đánh dấu CHƯA đọc. */
    public ResponseMessage markRead(String requestId, Integer accountId, JsonElement payload) {
        boolean read = readBoolean(payload, "read", true);
        return applyToEntries(requestId, accountId, payload,
                (entryId, owner) -> mailDAO.setRead(entryId, owner, read), EventName.MAIL_READ_UPDATED,
                flag("read", read), "Thư cần cập nhật trạng thái đọc không hợp lệ.",
                "Không tìm thấy thư trong hộp thư của bạn.", "Không thể cập nhật trạng thái đã đọc của thư.");
    }

    /** Payload {entryIds, starred}. */
    public ResponseMessage setStarred(String requestId, Integer accountId, JsonElement payload) {
        boolean starred = readBoolean(payload, "starred", true);
        return applyToEntries(requestId, accountId, payload,
                (entryId, owner) -> mailDAO.setStarred(entryId, owner, starred), EventName.MAIL_STARRED,
                flag("starred", starred), "Thư cần gắn sao không hợp lệ.",
                "Không tìm thấy thư trong hộp thư của bạn.", "Không thể cập nhật gắn sao.");
    }

    private static JsonObject flag(String name, boolean value) {
        JsonObject extra = new JsonObject();
        extra.addProperty(name, value);
        return extra;
    }

    private boolean readBoolean(JsonElement payload, String property, boolean defaultValue) {
        if (payload == null || !payload.isJsonObject() || !payload.getAsJsonObject().has(property)) {
            return defaultValue;
        }
        JsonElement value = payload.getAsJsonObject().get(property);
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : defaultValue;
    }

    public ResponseMessage deleteMail(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::moveMailToTrash, EventName.MAIL_DELETED, null,
                "Thư cần xóa không hợp lệ.", "Không tìm thấy thư trong hộp thư của bạn.",
                "Không thể chuyển thư vào Thùng rác.");
    }

    public ResponseMessage deleteTrashMail(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::deleteTrashMail, EventName.MAIL_DELETED, null,
                "Thư cần xóa không hợp lệ.", "Không tìm thấy thư trong Thùng rác của bạn.",
                "Không thể xóa vĩnh viễn thư trong Thùng rác.");
    }

    public ResponseMessage restoreTrashMail(String requestId, Integer accountId, JsonElement payload) {
        return applyToEntries(requestId, accountId, payload, mailDAO::restoreTrashMail, EventName.MAIL_RESTORED, null,
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
            EntryOperation operation, EventName eventName, JsonObject extraEventData, String invalidMessage,
            String notFoundMessage, String failureMessage) {
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
            if (extraEventData != null) {
                extraEventData.entrySet().forEach(entry -> eventData.add(entry.getKey(), entry.getValue()));
            }
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

    /**
     * Chỉ phân giải người nhận (email, nhóm → tài khoản), không ghi gì vào DB.
     * Dùng cho test: kiểm tra ai nhận thư mà không cần MySQL.
     */
    java.util.Set<Integer> resolveRecipientIds(JsonObject payload, int senderId) throws Exception {
        return resolveRecipients(payload, senderId).keySet();
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
        try {
            if (!FileSignatures.matches(upload.extension(), upload.file())) {
                throw new IllegalArgumentException("Nội dung tệp không đúng định dạng ."
                        + upload.extension() + ": " + upload.filename());
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Không đọc được tệp đã tải lên: " + upload.filename());
        }
        return new PreparedAttachment(upload.filename(), mimeType(upload.extension()), upload.extension(),
                actualSize, null, upload.file(), null);
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
        if (!FileSignatures.matches(extension, content)) {
            throw new IllegalArgumentException("Nội dung tệp không đúng định dạng ." + extension + ": " + filename);
        }
        return new PreparedAttachment(filename, mimeType(extension), extension, content.length, content, null, null);
    }

    /**
     * Tệp của thư gốc khi chuyển tiếp ({forwardAttachmentIds: [...]}): chỉ nhận
     * tệp mà người gửi có quyền xem; tính chung giới hạn số lượng và dung lượng.
     */
    private List<PreparedAttachment> parseForwardedAttachments(JsonObject payload, int accountId, long usedBytes,
            int usedCount) throws SQLException {
        List<PreparedAttachment> forwarded = new ArrayList<>();
        if (!payload.has("forwardAttachmentIds")) {
            return forwarded;
        }
        if (!payload.get("forwardAttachmentIds").isJsonArray()) {
            throw new IllegalArgumentException("Danh sách tệp chuyển tiếp không hợp lệ.");
        }
        long totalBytes = usedBytes;
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonElement element : payload.getAsJsonArray("forwardAttachmentIds")) {
            int attachmentId;
            try {
                attachmentId = element.getAsInt();
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Tệp chuyển tiếp không hợp lệ.");
            }
            if (!seen.add(attachmentId)) {
                continue;
            }
            if (usedCount + seen.size() > ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
                throw new IllegalArgumentException("Mỗi thư chỉ được đính kèm tối đa "
                        + ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL + " tệp.");
            }
            MailDAO.StoredAttachment stored = mailDAO.findAccessibleAttachment(attachmentId, accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tệp cần chuyển tiếp."));
            if (!Files.isRegularFile(stored.file())) {
                throw new IllegalArgumentException("Tệp " + stored.filename() + " không còn trên máy chủ.");
            }
            if (stored.sizeBytes() > ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES - totalBytes) {
                throw new IllegalArgumentException("Tổng dung lượng đính kèm không được vượt quá 25 MB.");
            }
            totalBytes += stored.sizeBytes();
            String filename = stored.filename();
            int dot = filename.lastIndexOf('.');
            String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
            forwarded.add(new PreparedAttachment(filename, stored.mimeType(), extension, stored.sizeBytes(),
                    null, null, stored.file()));
        }
        return forwarded;
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

    /** Đúng một nguồn có giá trị: content (dạng cũ), uploadedFile (di chuyển) hoặc forwardedFile (sao chép). */
    private record PreparedAttachment(String filename, String mimeType, String extension, long sizeBytes,
            byte[] content, Path uploadedFile, Path forwardedFile) {
    }

    private static class InvalidRecipientException extends Exception {
        InvalidRecipientException(String message) {
            super(message);
        }
    }
}
