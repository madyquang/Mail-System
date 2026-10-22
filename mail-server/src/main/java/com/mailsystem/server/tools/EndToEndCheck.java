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
            socket = (SSLSocket) trustAll().getSocketFactory().createSocket("localhost", port);
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
             java.sql.PreparedStatement accounts = connection.prepareStatement("DELETE FROM account WHERE email LIKE ?")) {
            groups.setString(1, "e2e-nhom-" + tag);
            groups.executeUpdate();
            accounts.setString(1, "e2e-%-" + tag + "@test.local");
            System.out.println("Da don " + accounts.executeUpdate() + " tai khoan kiem thu.");
        } catch (java.sql.SQLException error) {
            System.err.println("Khong don duoc du lieu kiem thu: " + error.getMessage());
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
            check(a1.req(Command.ADD_MEMBER, obj("groupId", groupId, "email", b)).isOk(), "ADD_MEMBER B");
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
            check(bInbox.size() == 3, "B co 3 thu trong INBOX");
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
