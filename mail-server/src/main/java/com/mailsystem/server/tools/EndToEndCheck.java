package com.mailsystem.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.*;
import com.mailsystem.server.db.DatabaseConnection;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/**
 * Kiểm thử END-TO-END toàn hệ thống: kết nối TLS tới một MailServer đang chạy
 * thật (kèm MySQL thật) và chạy ~50 kịch bản: đăng ký/đăng nhập, chống dò mật
 * khẩu, nhóm thư, upload/download theo khối, chặn tệp giả định dạng, gắn sao,
 * thư nháp, chuyển tiếp, real-time EVENT nhiều phiên, đổi mật khẩu + thu hồi phiên.
 *
 * Chạy: scripts/e2e.ps1 (tự khởi động Server riêng ở cổng 5098 rồi dọn dẹp).
 * Hoặc: EndToEndCheck [port]  — chỉ dùng trên máy có quyền truy cập MySQL của
 * Server, vì cuối cùng công cụ xoá các tài khoản e2e-*@test.local nó đã tạo.
 */
public class EndToEndCheck {
    static int failures = 0;
    static int checks = 0;
    static int port;
    static int monitorPort;
    static String monitorToken;
    /** Dấu vân tay thiết bị tạo trong lúc kiểm thử (xoá khi dọn dẹp). */
    static String deviceFingerprint;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    }

    static SSLContext trustAll() throws Exception {
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, new TrustManager[] {tm}, null);
        return ctx;
    }

    static final class Conn implements AutoCloseable {
        final SSLSocket socket;
        final Writer out;
        final Map<String, CompletableFuture<ResponseMessage>> pending = new ConcurrentHashMap<>();
        final BlockingQueue<EventMessage> events = new LinkedBlockingQueue<>();
        volatile boolean closedByServer;

        Conn() throws Exception {
            this(trustAll());
        }

        /** context có KeyManager = kết nối mutual TLS (trình chứng chỉ thiết bị). */
        Conn(SSLContext context) throws Exception {
            socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", port);
            socket.startHandshake();
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            FrameReader reader = new FrameReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    4 * ProtocolConstants.MAX_FRAME_CHARS);
            Thread t = new Thread(() -> {
                try {
                    String f;
                    while ((f = reader.readFrame()) != null) {
                        if (ProtocolUtil.peekType(f) == MessageType.EVENT) {
                            events.add(ProtocolUtil.fromJson(f, EventMessage.class));
                        } else {
                            ResponseMessage r = ProtocolUtil.fromJson(f, ResponseMessage.class);
                            CompletableFuture<ResponseMessage> fut = pending.remove(r.getRequestId());
                            if (fut != null) fut.complete(r);
                        }
                    }
                } catch (IOException ignored) {
                }
                closedByServer = true;
            });
            t.setDaemon(true);
            t.start();
        }

        String protocol() { return socket.getSession().getProtocol(); }

        ResponseMessage req(Command c, JsonObject payload) throws Exception {
            String id = UUID.randomUUID().toString();
            CompletableFuture<ResponseMessage> fut = new CompletableFuture<>();
            pending.put(id, fut);
            synchronized (out) {
                ProtocolUtil.writeFrame(out, ProtocolUtil.toJson(new RequestMessage(id, c, payload)));
            }
            return fut.get(20, TimeUnit.SECONDS);
        }

        EventMessage awaitEvent(String name, long ms) throws InterruptedException {
            long deadline = System.currentTimeMillis() + ms;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                EventMessage e = events.poll(left, TimeUnit.MILLISECONDS);
                if (e == null) return null;
                if (e.getEventName().equals(name)) return e;
            }
        }

        public void close() throws IOException { socket.close(); }
    }

    static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof JsonElement) o.add((String) kv[i], (JsonElement) v);
            else if (v instanceof Number) o.addProperty((String) kv[i], (Number) v);
            else if (v instanceof Boolean) o.addProperty((String) kv[i], (Boolean) v);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        return o;
    }

    static JsonArray arr(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            if (v instanceof Number) a.add((Number) v); else a.add(String.valueOf(v));
        }
        return a;
    }

    static int folderId(Conn c, String type) throws Exception {
        for (JsonElement f : c.req(Command.GET_FOLDERS, null).getData().getAsJsonArray())
            if (f.getAsJsonObject().get("folderType").getAsString().equals(type))
                return f.getAsJsonObject().get("folderId").getAsInt();
        throw new IllegalStateException(type);
    }

    static int unread(Conn c, String type) throws Exception {
        for (JsonElement f : c.req(Command.GET_FOLDERS, null).getData().getAsJsonArray())
            if (f.getAsJsonObject().get("folderType").getAsString().equals(type))
                return f.getAsJsonObject().get("unreadCount").getAsInt();
        return -1;
    }

    static JsonArray list(Conn c, String type) throws Exception {
        return c.req(Command.GET_MAIL_LIST, obj("folderId", folderId(c, type))).getData().getAsJsonArray();
    }

    static String upload(Conn c, String filename, byte[] file) throws Exception {
        String uploadId = null;
        long offset = 0;
        while (offset < file.length) {
            int len = (int) Math.min(ProtocolConstants.CHUNK_SIZE_BYTES, file.length - offset);
            JsonObject p = obj("filename", filename, "sizeBytes", file.length, "offset", offset,
                    "dataBase64", Base64.getEncoder().encodeToString(Arrays.copyOfRange(file, (int) offset, (int) offset + len)));
            if (uploadId != null) p.addProperty("uploadId", uploadId);
            ResponseMessage r = c.req(Command.UPLOAD_ATTACHMENT_CHUNK, p);
            if (!r.isOk()) throw new IllegalStateException(r.getMessage());
            uploadId = r.getData().getAsJsonObject().get("uploadId").getAsString();
            offset = r.getData().getAsJsonObject().get("receivedBytes").getAsLong();
        }
        return uploadId;
    }

    static byte[] download(Conn c, int attachmentId, long size) throws Exception {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        while (got.size() < size) {
            ResponseMessage chunk = c.req(Command.DOWNLOAD_ATTACHMENT, obj("attachmentId", attachmentId, "offset", got.size()));
            if (!chunk.isOk()) throw new IllegalStateException(chunk.getMessage());
            got.write(Base64.getDecoder().decode(chunk.getData().getAsJsonObject().get("dataBase64").getAsString()));
        }
        return got.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        port = args.length > 0 ? Integer.parseInt(args[0]) : 5098;
        monitorPort = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        monitorToken = args.length > 2 ? args[2] : "";
        String tag = Long.toString(System.currentTimeMillis() % 100_000_000L);
        try {
            run(tag);
        } finally {
            cleanup(tag);
        }
        System.out.println();
        System.out.println("KET QUA: " + (checks - failures) + "/" + checks + " dat");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Xoá mọi dữ liệu kiểm thử (tài khoản + nhóm; thư/nháp xoá theo khoá ngoại CASCADE). */
    static void cleanup(String tag) {
        try (java.sql.Connection connection = DatabaseConnection.getConnection();
             java.sql.PreparedStatement groups = connection.prepareStatement("DELETE FROM mail_group WHERE group_name = ?");
             java.sql.PreparedStatement accounts = connection.prepareStatement("DELETE FROM account WHERE email LIKE ?");
             java.sql.PreparedStatement attempts = connection.prepareStatement("DELETE FROM login_attempt WHERE attempt_key LIKE ?");
             java.sql.PreparedStatement devices = connection.prepareStatement("DELETE FROM trusted_device WHERE fingerprint = ?")) {
            groups.setString(1, "e2e-nhom-" + tag);
            groups.executeUpdate();
            attempts.setString(1, "email:e2e-%-" + tag + "@test.local");
            attempts.executeUpdate();
            devices.setString(1, String.valueOf(deviceFingerprint));
            devices.executeUpdate();
            accounts.setString(1, "e2e-%-" + tag + "@test.local");
            System.out.println("Da don " + accounts.executeUpdate() + " tai khoan kiem thu.");
        } catch (java.sql.SQLException error) {
            System.err.println("Khong don duoc du lieu kiem thu: " + error.getMessage());
        }
    }

    static JsonObject data(ResponseMessage r) {
        return r.getData() != null && r.getData().isJsonObject() ? r.getData().getAsJsonObject() : new JsonObject();
    }

    static JsonObject findBySubject(JsonArray mails, String subject) {
        for (JsonElement e : mails) {
            if (e.getAsJsonObject().get("subject").getAsString().equals(subject)) return e.getAsJsonObject();
        }
        return null;
    }

    /** Các tính năng v3: tiếp tục phiên, thư mục, hội thoại, báo đã đọc, hoàn tác/hẹn giờ, tải tiếp, 2FA, mTLS, giám sát. */
    static void runV3(String tag, String pw, String a, String b, Conn a1, Conn b1, int fileEntry) throws Exception {
        System.out.println("== v3: Tiep tuc phien sau khi rot mang (RESUME_SESSION)");
        String token1;
        try (Conn r1 = new Conn()) {
            ResponseMessage login = r1.req(Command.LOGIN, obj("email", b, "password", pw));
            token1 = data(login).has("resumeToken") ? data(login).get("resumeToken").getAsString() : null;
            check(token1 != null && token1.length() >= 40, "LOGIN tra resumeToken");
        } // đóng socket đột ngột = mô phỏng rớt mạng
        try (Conn r2 = new Conn(); Conn r3 = new Conn()) {
            ResponseMessage resumed = r2.req(Command.RESUME_SESSION, obj("resumeToken", token1));
            check(resumed.isOk() && data(resumed).get("email").getAsString().equals(b), "RESUME_SESSION dang nhap lai khong can mat khau");
            check(!token1.equals(data(resumed).get("resumeToken").getAsString()), "Server cap token MOI (token xoay vong)");
            check(r2.req(Command.GET_FOLDERS, null).isOk(), "Phien tiep tuc dung duoc lenh can dang nhap");
            ResponseMessage replay = r3.req(Command.RESUME_SESSION, obj("resumeToken", token1));
            check(!replay.isOk() && ErrorCodes.RESUME_REJECTED.equals(replay.getCode()), "Dung lai token cu bi tu choi (mot lan)");
        }

        System.out.println("== v3: Thu muc tu tao + chuyen thu");
        ResponseMessage created = b1.req(Command.CREATE_FOLDER, obj("name", "Dự án " + tag));
        check(created.isOk(), "CREATE_FOLDER");
        int folder = data(created).get("folderId").getAsInt();
        check(!b1.req(Command.CREATE_FOLDER, obj("name", "Dự án " + tag)).isOk(), "Trung ten thu muc bi tu choi");
        check(b1.req(Command.MOVE_MAIL, obj("entryIds", arr(fileEntry), "folderId", folder)).isOk(), "MOVE_MAIL vao thu muc");
        JsonArray inFolder = b1.req(Command.GET_MAIL_LIST, obj("folderId", folder)).getData().getAsJsonArray();
        check(inFolder.size() == 1 && inFolder.get(0).getAsJsonObject().get("entryId").getAsInt() == fileEntry, "Thu nam trong thu muc moi");
        check(!a1.req(Command.MOVE_MAIL, obj("entryIds", arr(fileEntry), "folderId", folder)).isOk(), "Khong chuyen duoc thu cua nguoi khac");
        check(b1.req(Command.RENAME_FOLDER, obj("folderId", folder, "name", "Dự án (đổi) " + tag)).isOk(), "RENAME_FOLDER");
        check(!b1.req(Command.RENAME_FOLDER, obj("folderId", folderId(b1, "INBOX"), "name", "x")).isOk(), "Khong doi ten thu muc he thong");
        ResponseMessage deleted = b1.req(Command.DELETE_FOLDER, obj("folderId", folder));
        check(deleted.isOk() && data(deleted).get("movedToTrash").getAsInt() == 1, "DELETE_FOLDER chuyen thu vao Thung rac");
        check(b1.req(Command.RESTORE_TRASH_MAIL, obj("entryIds", arr(fileEntry))).isOk(), "Khoi phuc thu tu Thung rac");

        System.out.println("== v3: Bao da doc");
        a1.events.clear();
        check(a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Đọc chưa " + tag, "body", "?")).isOk(), "A gui thu cho B");
        JsonObject toRead = findBySubject(list(b1, "INBOX"), "Đọc chưa " + tag);
        b1.req(Command.GET_MAIL_DETAIL, obj("mailId", toRead.get("mailId").getAsInt(), "entryId", toRead.get("entryId").getAsInt()));
        EventMessage receipt = a1.awaitEvent("READ_RECEIPT", 3000);
        check(receipt != null && receipt.getData().getAsJsonObject().get("readerEmail").getAsString().equals(b),
                "A nhan EVENT READ_RECEIPT khi B mo thu");
        JsonObject sentCopy = findBySubject(list(a1, "SENT"), "Đọc chưa " + tag);
        JsonObject sentDetail = data(a1.req(Command.GET_MAIL_DETAIL, obj("mailId", sentCopy.get("mailId").getAsInt(),
                "entryId", sentCopy.get("entryId").getAsInt())));
        check(sentDetail.has("readReceipts") && sentDetail.getAsJsonArray("readReceipts").size() == 1,
                "Ban da gui cua A co danh sach nguoi da doc");
        check(b1.req(Command.UPDATE_SETTINGS, obj("shareReadReceipts", false)).isOk(), "B tat gui xac nhan da doc");
        check(!data(b1.req(Command.GET_SETTINGS, null)).get("shareReadReceipts").getAsBoolean(), "GET_SETTINGS phan anh thay doi");
        a1.events.clear();
        a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Riêng tư " + tag, "body", "?"));
        JsonObject privateMail = findBySubject(list(b1, "INBOX"), "Riêng tư " + tag);
        b1.req(Command.GET_MAIL_DETAIL, obj("mailId", privateMail.get("mailId").getAsInt(), "entryId", privateMail.get("entryId").getAsInt()));
        check(a1.awaitEvent("READ_RECEIPT", 1500) == null, "Tat xac nhan -> nguoi gui khong duoc bao");
        b1.req(Command.UPDATE_SETTINGS, obj("shareReadReceipts", true));

        System.out.println("== v3: Hoi thoai (thread)");
        a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Họp nhóm " + tag, "body", "Thứ 2 nhé?"));
        JsonObject first = findBySubject(list(b1, "INBOX"), "Họp nhóm " + tag);
        int rootMail = first.get("mailId").getAsInt();
        check(b1.req(Command.SEND_MAIL, obj("to", arr(a), "subject", "Re: Họp nhóm " + tag, "body", "OK", "inReplyTo", rootMail)).isOk(),
                "B tra loi (inReplyTo)");
        JsonObject reply = findBySubject(list(a1, "INBOX"), "Re: Họp nhóm " + tag);
        check(reply != null && reply.get("threadId").getAsInt() == rootMail, "Thu tra loi cung threadId voi thu goc");
        a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Re: Họp nhóm " + tag, "body", "Chốt 9h",
                "inReplyTo", reply.get("mailId").getAsInt()));
        JsonArray threaded = b1.req(Command.GET_MAIL_LIST, obj("folderId", folderId(b1, "INBOX"), "threaded", true)).getData().getAsJsonArray();
        JsonObject head = null;
        for (JsonElement e : threaded) if (e.getAsJsonObject().get("threadId").getAsInt() == rootMail) head = e.getAsJsonObject();
        check(head != null && head.get("threadCount").getAsInt() == 2 && head.getAsJsonArray("threadEntryIds").size() == 2,
                "Danh sach gop hoi thoai: 1 dong, 2 thu trong Hop thu den");
        JsonArray thread = b1.req(Command.GET_THREAD, obj("threadId", rootMail)).getData().getAsJsonArray();
        check(thread.size() == 3 && thread.get(0).getAsJsonObject().get("mailId").getAsInt() == rootMail,
                "GET_THREAD tra 3 thu (ca thu da gui), cu truoc moi sau");
        check(!a1.req(Command.GET_THREAD, obj("threadId", fileEntry + 999_999)).isOk(), "Hoi thoai khong co quyen xem -> loi");

        System.out.println("== v3: Hoan tac gui / hen gio");
        ResponseMessage queued = a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Hoàn tác " + tag, "body", "x", "delaySeconds", 30));
        check(queued.isOk() && data(queued).has("outboxId"), "Gui co delaySeconds -> vao hop cho gui");
        int outboxId = data(queued).get("outboxId").getAsInt();
        JsonArray pending = a1.req(Command.GET_SCHEDULED, null).getData().getAsJsonArray();
        check(pending.size() == 1 && pending.get(0).getAsJsonObject().get("subject").getAsString().equals("Hoàn tác " + tag), "GET_SCHEDULED");
        check(!b1.req(Command.CANCEL_SCHEDULED, obj("outboxId", outboxId)).isOk(), "Nguoi khac khong huy duoc thu cho");
        ResponseMessage undone = a1.req(Command.CANCEL_SCHEDULED, obj("outboxId", outboxId));
        check(undone.isOk() && data(undone).get("subject").getAsString().equals("Hoàn tác " + tag), "CANCEL_SCHEDULED tra lai noi dung");
        check(!a1.req(Command.CANCEL_SCHEDULED, obj("outboxId", outboxId)).isOk(), "Huy lan 2 bao loi (da huy)");
        check(findBySubject(list(b1, "INBOX"), "Hoàn tác " + tag) == null, "Thu da hoan tac khong toi nguoi nhan");
        check(!a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "x", "body", "x", "sendAt", "2020-01-01T08:00:00")).isOk(),
                "Hen gio o qua khu bi tu choi");
        ResponseMessage future = a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Hẹn " + tag, "body", "x",
                "sendAt", java.time.LocalDateTime.now().plusDays(1).withNano(0).toString()));
        check(future.isOk() && data(future).get("scheduled").getAsBoolean(), "Hen gio ngay mai");
        a1.req(Command.CANCEL_SCHEDULED, obj("outboxId", data(future).get("outboxId").getAsInt()));
        byte[] note = "ghi chu dinh kem\n".getBytes(StandardCharsets.UTF_8);
        JsonArray noteAtt = new JsonArray();
        noteAtt.add(obj("uploadId", upload(a1, "ghi-chu.txt", note)));
        b1.events.clear();
        check(a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Trễ 2 giây " + tag, "body", "x", "delaySeconds", 2,
                "attachments", noteAtt)).isOk(), "Gui tre 2 giay kem tep");
        EventMessage delayed = b1.awaitEvent("NEW_MAIL", 8000);
        check(delayed != null && delayed.getData().getAsJsonObject().get("subject").getAsString().equals("Trễ 2 giây " + tag),
                "Bo dieu phoi gui thu khi het thoi gian cho");
        JsonObject delayedMail = findBySubject(list(b1, "INBOX"), "Trễ 2 giây " + tag);
        check(delayedMail != null && delayedMail.get("hasAttachments").getAsBoolean(), "Thu gui tre giu nguyen tep dinh kem");

        System.out.println("== v3: Tai len tiep sau khi rot mang (UPLOAD_STATUS)");
        byte[] big = new byte[ProtocolConstants.CHUNK_SIZE_BYTES + 1000];
        new Random(3).nextBytes(big);
        System.arraycopy(new byte[] {'P', 'K', 3, 4}, 0, big, 0, 4);
        String partial;
        try (Conn u1 = new Conn()) {
            u1.req(Command.LOGIN, obj("email", b, "password", pw));
            ResponseMessage chunk = u1.req(Command.UPLOAD_ATTACHMENT_CHUNK, obj("filename", "du-lieu.zip", "sizeBytes", big.length,
                    "offset", 0, "dataBase64", Base64.getEncoder().encodeToString(Arrays.copyOf(big, ProtocolConstants.CHUNK_SIZE_BYTES))));
            partial = data(chunk).get("uploadId").getAsString();
        }
        try (Conn u2 = new Conn()) {
            u2.req(Command.LOGIN, obj("email", b, "password", pw));
            ResponseMessage status = u2.req(Command.UPLOAD_STATUS, obj("uploadId", partial));
            check(status.isOk() && data(status).get("receivedBytes").getAsLong() == ProtocolConstants.CHUNK_SIZE_BYTES,
                    "Ket noi moi thay Server da nhan khoi 1");
            ResponseMessage rest = u2.req(Command.UPLOAD_ATTACHMENT_CHUNK, obj("uploadId", partial, "filename", "du-lieu.zip",
                    "sizeBytes", big.length, "offset", ProtocolConstants.CHUNK_SIZE_BYTES, "dataBase64",
                    Base64.getEncoder().encodeToString(Arrays.copyOfRange(big, ProtocolConstants.CHUNK_SIZE_BYTES, big.length))));
            check(rest.isOk() && data(rest).get("complete").getAsBoolean(), "Gui tiep phan con lai thanh cong");
            JsonArray resumedAtt = new JsonArray();
            resumedAtt.add(obj("uploadId", partial));
            check(u2.req(Command.SEND_MAIL, obj("to", arr(a), "subject", "Tải tiếp " + tag, "body", "x", "attachments", resumedAtt)).isOk(),
                    "Gui thu voi tep tai len qua 2 ket noi");
        }
        try (Conn other = new Conn()) {
            other.req(Command.LOGIN, obj("email", a, "password", pw));
            check(!other.req(Command.UPLOAD_STATUS, obj("uploadId", partial)).isOk(), "Tai khoan khac khong xem duoc tien do tai len");
        }

        System.out.println("== v3: Xac minh 2 buoc (TOTP)");
        String e = "e2e-e-" + tag + "@test.local";
        try (Conn e1 = new Conn()) {
            e1.req(Command.REGISTER, obj("email", e, "password", pw, "displayName", "E"));
            e1.req(Command.LOGIN, obj("email", e, "password", pw));
            ResponseMessage setup = e1.req(Command.TOTP_SETUP, null);
            String secret = data(setup).get("secret").getAsString();
            check(setup.isOk() && data(setup).get("otpauthUri").getAsString().startsWith("otpauth://totp/"), "TOTP_SETUP tra khoa + otpauth URI");
            long step = System.currentTimeMillis() / 1000 / com.mailsystem.server.security.Totp.STEP_SECONDS;
            check(!e1.req(Command.TOTP_ENABLE, obj("code", "000000".equals(com.mailsystem.server.security.Totp.code(secret, step))
                    ? "111111" : "000000")).isOk(), "Ma sai khong bat duoc 2FA");
            check(e1.req(Command.TOTP_ENABLE, obj("code", com.mailsystem.server.security.Totp.code(secret, step))).isOk(), "TOTP_ENABLE");
            try (Conn e2 = new Conn(); Conn e3 = new Conn()) {
                ResponseMessage noCode = e2.req(Command.LOGIN, obj("email", e, "password", pw));
                check(!noCode.isOk() && ErrorCodes.TOTP_REQUIRED.equals(noCode.getCode()), "Thieu ma -> TOTP_REQUIRED");
                String next = com.mailsystem.server.security.Totp.code(secret, step + 1);
                check(e2.req(Command.LOGIN, obj("email", e, "password", pw, "totpCode", next)).isOk(), "Mat khau + ma dung -> dang nhap");
                check(!e3.req(Command.LOGIN, obj("email", e, "password", pw, "totpCode", next)).isOk(), "Dung lai cung ma bi tu choi (chong replay)");
            }
        }

        System.out.println("== v3: Chung chi thiet bi (mutual TLS) + bang giam sat");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("e2e-device");
        java.nio.file.Path p12 = dir.resolve("device.p12");
        String keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process gen = new ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "device", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=e2e-" + tag, "-validity", "2", "-storetype", "PKCS12",
                "-keystore", p12.toString(), "-storepass", "e2e-pass", "-keypass", "e2e-pass").redirectErrorStream(true).start();
        gen.getInputStream().readAllBytes();
        gen.waitFor(60, TimeUnit.SECONDS);
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (InputStream in = java.nio.file.Files.newInputStream(p12)) {
            store.load(in, "e2e-pass".toCharArray());
        }
        deviceFingerprint = com.mailsystem.common.security.CertificateFingerprints.sha256(
                (X509Certificate) store.getCertificate("device"));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "e2e-pass".toCharArray());
        SSLContext mutual = SSLContext.getInstance("TLSv1.3");
        mutual.init(kmf.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String x) { }
            public void checkServerTrusted(X509Certificate[] c, String x) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (Conn m1 = new Conn(mutual)) {
            check(m1.req(Command.LOGIN, obj("email", b, "password", pw)).isOk(), "Ket noi co chung chi thiet bi dang nhap duoc");
            check(m1.socket.getSession().getLocalCertificates() != null, "Client da trinh chung chi trong bat tay TLS");
        }
        boolean recorded;
        try (java.sql.Connection db = DatabaseConnection.getConnection();
             java.sql.PreparedStatement q = db.prepareStatement("SELECT approved, last_account_id FROM trusted_device WHERE fingerprint = ?")) {
            q.setString(1, deviceFingerprint);
            try (java.sql.ResultSet rs = q.executeQuery()) {
                recorded = rs.next() && !rs.getBoolean("approved") && rs.getInt("last_account_id") > 0;
            }
        }
        check(recorded, "Server ghi nhan thiet bi moi (chua duyet) + tai khoan dung no");

        if (monitorPort > 0) {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            java.net.URI stats = java.net.URI.create("http://127.0.0.1:" + monitorPort + "/api/stats");
            java.net.http.HttpResponse<String> noToken = http.send(java.net.http.HttpRequest.newBuilder(stats).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            check(noToken.statusCode() == 401, "Bang giam sat: thieu ma truy cap -> 401");
            java.net.http.HttpResponse<String> withToken = http.send(java.net.http.HttpRequest.newBuilder(stats)
                    .header("X-Admin-Token", monitorToken).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonObject snapshot = ProtocolUtil.fromJson(withToken.body(), JsonObject.class);
            check(withToken.statusCode() == 200 && snapshot.getAsJsonArray("connections").size() >= 2,
                    "Bang giam sat liet ke cac ket noi dang mo");
            check(snapshot.getAsJsonObject("metrics").get("requests").getAsLong() > 50, "Bang giam sat dem request");
            java.net.http.HttpResponse<String> approve = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "http://127.0.0.1:" + monitorPort + "/api/devices/approve?fp=" + java.net.URLEncoder.encode(deviceFingerprint,
                            StandardCharsets.UTF_8))).header("X-Admin-Token", monitorToken)
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            check(approve.statusCode() == 200 && approve.body().contains("true"), "Quan tri vien phe duyet thiet bi qua bang giam sat");
        }
    }

    static void run(String tag) throws Exception {
        String pw = "Matkhau-2026";
        String a = "e2e-a-" + tag + "@test.local", b = "e2e-b-" + tag + "@test.local", c = "e2e-c-" + tag + "@test.local";
        String group = "e2e-nhom-" + tag;

        try (Conn a1 = new Conn(); Conn a2 = new Conn(); Conn b1 = new Conn(); Conn c1 = new Conn()) {
            System.out.println("== TLS");
            check("TLSv1.3".equals(a1.protocol()), "Ket noi dung TLS 1.3 (" + a1.socket.getSession().getCipherSuite() + ")");
            try (Socket plain = new Socket("localhost", port)) {
                plain.setSoTimeout(5000);
                plain.getOutputStream().write("{\"type\":\"REQUEST\",\"requestId\":\"x\",\"command\":\"PING\"}\n".getBytes());
                int first = plain.getInputStream().read();
                check(first == -1 || first == 0x15, "Client TCP thuong (khong TLS) bi tu choi");
            }

            System.out.println("== Dang ky (chinh sach mat khau) / dang nhap");
            ResponseMessage weak = a1.req(Command.REGISTER, obj("email", a, "password", "123456", "displayName", "A"));
            check(!weak.isOk() && weak.getMessage().contains("8 ký tự"), "Mat khau yeu bi tu choi: " + weak.getMessage());
            check(a1.req(Command.REGISTER, obj("email", a, "password", pw, "displayName", "Nguyễn Văn A")).isOk(), "REGISTER A");
            check(b1.req(Command.REGISTER, obj("email", b, "password", pw, "displayName", "Trần Thị B")).isOk(), "REGISTER B");
            check(c1.req(Command.REGISTER, obj("email", c, "password", pw, "displayName", "Lê C")).isOk(), "REGISTER C");
            check(a1.req(Command.LOGIN, obj("email", a, "password", pw)).isOk(), "LOGIN A (phien 1)");
            check(a2.req(Command.LOGIN, obj("email", a, "password", pw)).isOk(), "LOGIN A (phien 2)");
            check(b1.req(Command.LOGIN, obj("email", b, "password", pw)).isOk(), "LOGIN B");

            System.out.println("== Chong do mat khau");
            ResponseMessage last = null;
            for (int i = 0; i < 5; i++) last = c1.req(Command.LOGIN, obj("email", c, "password", "sai-" + i));
            check(last != null && last.getMessage().contains("quá nhiều lần"), "Sai 5 lan -> tam khoa: " + last.getMessage());
            ResponseMessage blocked = c1.req(Command.LOGIN, obj("email", c, "password", pw));
            check(!blocked.isOk() && blocked.getMessage().contains("quá nhiều lần"), "Dang bi khoa thi mat khau dung cung bi chan");

            System.out.println("== Nhom + upload + gui");
            int groupId = a1.req(Command.CREATE_GROUP, obj("groupName", group)).getData().getAsJsonObject().get("groupId").getAsInt();
            b1.events.clear();
            check(a1.req(Command.ADD_MEMBER, obj("groupId", groupId, "email", b)).isOk(), "ADD_MEMBER B");
            EventMessage added = b1.awaitEvent("GROUP_UPDATED", 3000);
            check(added != null && added.getData().getAsJsonObject().get("action").getAsString().equals("ADDED")
                    && added.getData().getAsJsonObject().get("groupName").getAsString().equals(group),
                    "B nhan EVENT GROUP_UPDATED (ADDED) khi duoc them vao nhom");
            JsonObject notice = findBySubject(list(b1, "INBOX"), "Bạn đã được thêm vào nhóm \"" + group + "\"");
            check(notice != null && notice.get("senderEmail").getAsString().equals(a),
                    "B co thu thong bao trong Hop thu den (gui danh nghia chu nhom)");
            check(findBySubject(list(a1, "SENT"), "Bạn đã được thêm vào nhóm \"" + group + "\"") == null,
                    "Thu thong bao khong lam day muc Da gui cua chu nhom");
            byte[] pdf = new byte[900_000];
            new Random(7).nextBytes(pdf);
            System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, pdf, 0, 9);
            String uploadId = upload(a1, "báo cáo.pdf", pdf);
            b1.events.clear();
            ResponseMessage sent = a1.req(Command.SEND_MAIL, obj("to", arr(group), "subject", "Báo cáo tuần 🙂",
                    "body", "Dòng 1\nDòng 2", "attachments", arr(), "forwardAttachmentIds", arr()));
            check(sent.isOk(), "Gui thu khong tep toi nhom");
            JsonArray atts = new JsonArray();
            atts.add(obj("uploadId", uploadId));
            ResponseMessage sentWithFile = a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Kèm tệp", "body", "Xem tệp", "attachments", atts));
            check(sentWithFile.isOk(), "Gui thu kem PDF 900KB");
            EventMessage ev = b1.awaitEvent("NEW_MAIL", 3000);
            check(ev != null && ev.getData().getAsJsonObject().get("senderName").getAsString().equals("Nguyễn Văn A"),
                    "EVENT NEW_MAIL mang ten nguoi gui (cho thong bao desktop)");

            byte[] exe = new byte[2048];
            exe[0] = 'M'; exe[1] = 'Z';
            String fakeId = upload(a1, "hoa-don.pdf", exe);
            JsonArray fake = new JsonArray();
            fake.add(obj("uploadId", fakeId));
            ResponseMessage fakeSend = a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "x", "body", "x", "attachments", fake));
            check(!fakeSend.isOk() && fakeSend.getMessage().contains("không đúng định dạng"), "Tep .exe doi ten .pdf bi chan: " + fakeSend.getMessage());

            StringBuilder longBody = new StringBuilder();
            while (longBody.length() < 60_000) longBody.append("Nội dung tiếng Việt rất dài. ");
            check(a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Thư dài", "body", longBody.toString())).isOk(),
                    "Noi dung 60 000 ky tu tieng Viet (MEDIUMTEXT) gui duoc");
            check(!a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "x", "body", "a".repeat(100_001))).isOk(),
                    "Noi dung > 100 000 ky tu bi tu choi");

            System.out.println("== Gan sao / chua doc / tim kiem");
            JsonArray bInbox = list(b1, "INBOX");
            check(bInbox.size() == 4, "B co 4 thu trong INBOX (3 thu + 1 thu thong bao vao nhom)");
            int fileEntry = -1, fileMail = -1;
            for (JsonElement e : bInbox) {
                JsonObject m = e.getAsJsonObject();
                if (m.get("subject").getAsString().equals("Kèm tệp")) {
                    fileEntry = m.get("entryId").getAsInt();
                    fileMail = m.get("mailId").getAsInt();
                    check(m.get("hasAttachments").getAsBoolean() && m.get("snippet").getAsString().equals("Xem tệp"),
                            "MailSummary co hasAttachments + snippet");
                }
            }
            check(b1.req(Command.SET_STARRED, obj("entryIds", arr(fileEntry), "starred", true)).isOk(), "Gan sao");
            JsonArray starred = b1.req(Command.GET_MAIL_LIST, obj("view", "STARRED")).getData().getAsJsonArray();
            check(starred.size() == 1 && starred.get(0).getAsJsonObject().get("folderType").getAsString().equals("INBOX"),
                    "View STARRED tra dung 1 thu kem folderType");
            JsonObject detail = b1.req(Command.GET_MAIL_DETAIL, obj("mailId", fileMail, "entryId", fileEntry)).getData().getAsJsonObject();
            check(detail.get("starred").getAsBoolean() && detail.get("entryId").getAsInt() == fileEntry, "MailDetail co starred + entryId");
            int unreadAfterOpen = unread(b1, "INBOX");
            check(b1.req(Command.MARK_READ, obj("entryIds", arr(fileEntry), "read", false)).isOk(), "Danh dau CHUA doc");
            check(unread(b1, "INBOX") == unreadAfterOpen + 1, "unreadCount tang lai 1");
            int attachmentId = detail.getAsJsonArray("attachments").get(0).getAsJsonObject().get("attachmentId").getAsInt();
            check(Arrays.equals(download(b1, attachmentId, pdf.length), pdf), "Tai PDF ve trung khop tung byte");

            System.out.println("== Chuyen tiep kem tep");
            check(c1.req(Command.PING, null).isOk(), "(C van ket noi duoc du dang bi khoa dang nhap)");
            // Mở khoá C: đăng ký tài khoản D thay thế để nhận thư chuyển tiếp.
            String d = "e2e-d-" + tag + "@test.local";
            try (Conn d1 = new Conn()) {
                check(d1.req(Command.REGISTER, obj("email", d, "password", pw, "displayName", "Phạm D")).isOk(), "REGISTER D");
                check(d1.req(Command.LOGIN, obj("email", d, "password", pw)).isOk(), "LOGIN D");
                ResponseMessage fw = b1.req(Command.SEND_MAIL, obj("to", arr(d), "subject", "Fwd: Kèm tệp", "body", "chuyển tiếp",
                        "forwardAttachmentIds", arr(attachmentId)));
                check(fw.isOk(), "B chuyen tiep thu kem tep goc cho D");
                JsonObject dm = list(d1, "INBOX").get(0).getAsJsonObject();
                JsonObject dd = d1.req(Command.GET_MAIL_DETAIL, obj("mailId", dm.get("mailId").getAsInt(), "entryId",
                        dm.get("entryId").getAsInt())).getData().getAsJsonObject();
                int dAtt = dd.getAsJsonArray("attachments").get(0).getAsJsonObject().get("attachmentId").getAsInt();
                check(dAtt != attachmentId && Arrays.equals(download(d1, dAtt, pdf.length), pdf), "D tai duoc ban sao rieng cua tep");
                ResponseMessage steal = d1.req(Command.SEND_MAIL, obj("to", arr(d), "subject", "x", "body", "x",
                        "forwardAttachmentIds", arr(attachmentId)));
                check(!steal.isOk(), "Khong chuyen tiep duoc tep cua thu minh khong co quyen xem");
            }

            System.out.println("== Goi y nguoi nhan (autocomplete)");
            JsonArray sug = a1.req(Command.SUGGEST_CONTACTS, obj("query", "e2e-b-" + tag.substring(0, 3))).getData().getAsJsonArray();
            boolean foundB = false;
            for (JsonElement e : sug) foundB |= b.equals(e.getAsJsonObject().has("email") ? e.getAsJsonObject().get("email").getAsString() : "");
            check(foundB, "Go vai ky tu email -> goi y dung nguoi");
            JsonArray byName = a1.req(Command.SUGGEST_CONTACTS, obj("query", "Trần Thị")).getData().getAsJsonArray();
            check(byName.size() >= 1 && byName.get(0).getAsJsonObject().get("email").getAsString().equals(b),
                    "Tim theo ten hien thi co dau; nguoi da trao doi thu xep dau");
            check(byName.get(0).getAsJsonObject().get("interactions").getAsInt() > 0, "Co so thu da trao doi");
            JsonArray groupSug = a1.req(Command.SUGGEST_CONTACTS, obj("query", "e2e-nhom-")).getData().getAsJsonArray();
            check(groupSug.size() >= 1 && groupSug.get(0).getAsJsonObject().get("kind").getAsString().equals("GROUP"),
                    "Goi y nhom minh la thanh vien");
            JsonArray selfSug = a1.req(Command.SUGGEST_CONTACTS, obj("query", a)).getData().getAsJsonArray();
            check(selfSug.size() == 0, "Khong goi y chinh minh");

            System.out.println("== Thu nhap");
            a1.events.clear(); a2.events.clear();
            ResponseMessage saved = a1.req(Command.SAVE_DRAFT, obj("to", b, "cc", "", "bcc", "", "subject", "Nháp 1", "body", "đang viết"));
            check(saved.isOk(), "SAVE_DRAFT moi");
            int draftId = saved.getData().getAsJsonObject().get("draftId").getAsInt();
            check(a2.awaitEvent("DRAFTS_UPDATED", 3000) != null, "Phien 2 nhan DRAFTS_UPDATED");
            check(a1.req(Command.SAVE_DRAFT, obj("draftId", draftId, "to", b, "subject", "Nháp 1 (sửa)", "body", "xong")).isOk(), "Cap nhat nhap");
            JsonArray drafts = a1.req(Command.GET_DRAFTS, null).getData().getAsJsonArray();
            check(drafts.size() == 1 && drafts.get(0).getAsJsonObject().get("subject").getAsString().equals("Nháp 1 (sửa)"), "GET_DRAFTS");
            check(!b1.req(Command.DELETE_DRAFTS, obj("draftIds", arr(draftId))).isOk(), "B khong xoa duoc nhap cua A");
            check(a1.req(Command.SEND_MAIL, obj("to", arr(b), "subject", "Nháp 1 (sửa)", "body", "xong", "draftId", draftId)).isOk(), "Gui tu nhap");
            check(a1.req(Command.GET_DRAFTS, null).getData().getAsJsonArray().size() == 0, "Gui xong thi nhap bi xoa");

            runV3(tag, pw, a, b, a1, b1, fileEntry);

            System.out.println("== Ho so / doi mat khau / thu hoi phien");
            a2.events.clear();
            check(a1.req(Command.UPDATE_PROFILE, obj("displayName", "Nguyễn Văn A (mới)")).isOk(), "UPDATE_PROFILE");
            EventMessage prof = a2.awaitEvent("PROFILE_UPDATED", 3000);
            check(prof != null && prof.getData().getAsJsonObject().get("displayName").getAsString().equals("Nguyễn Văn A (mới)"),
                    "Phien 2 nhan PROFILE_UPDATED");
            ResponseMessage wrongCurrent = a1.req(Command.CHANGE_PASSWORD, obj("currentPassword", "sai", "newPassword", "MatKhauMoi-2027"));
            check(!wrongCurrent.isOk() && wrongCurrent.getMessage().contains("hiện tại không đúng"), "Doi mat khau sai mat khau cu bi chan");
            ResponseMessage changed = a1.req(Command.CHANGE_PASSWORD, obj("currentPassword", pw, "newPassword", "MatKhauMoi-2027"));
            check(changed.isOk() && changed.getData().getAsJsonObject().get("revokedSessions").getAsInt() == 1, "Doi mat khau -> thu hoi 1 phien khac");
            EventMessage revoked = a2.awaitEvent("SESSION_REVOKED", 3000);
            check(revoked != null, "Phien 2 nhan SESSION_REVOKED");
            Thread.sleep(500);
            check(a2.closedByServer, "Server dong ket noi cua phien bi thu hoi");
            check(a1.req(Command.GET_FOLDERS, null).isOk(), "Phien doi mat khau van dung tiep");
            try (Conn a3 = new Conn()) {
                check(!a3.req(Command.LOGIN, obj("email", a, "password", pw)).isOk(), "Mat khau cu khong dang nhap duoc");
                check(a3.req(Command.LOGIN, obj("email", a, "password", "MatKhauMoi-2027")).isOk(), "Mat khau moi dang nhap duoc");
                a3.events.clear();
                ResponseMessage all = a1.req(Command.LOGOUT_ALL, null);
                check(all.isOk() && all.getData().getAsJsonObject().get("revokedSessions").getAsInt() == 1, "LOGOUT_ALL thu hoi phien con lai");
                check(a3.awaitEvent("SESSION_REVOKED", 3000) != null, "Phien 3 nhan SESSION_REVOKED");
                check(!a1.req(Command.GET_FOLDERS, null).isOk(), "Phien goi LOGOUT_ALL cung bi dang xuat");
            }
        }
    }
}
