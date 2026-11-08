// Gói "tools": các công cụ thử nghiệm Server (không thuộc chương trình chính).
package com.mailsystem.server.tools;

// JSON, giao thức dùng chung.
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.FrameReader;
import com.mailsystem.common.protocol.MessageType;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.RequestMessage;
import com.mailsystem.common.protocol.ResponseMessage;
// Kết nối CSDL (để dọn tài khoản thử nghiệm).
import com.mailsystem.server.db.DatabaseConnection;

// Vào/ra, bảng mã, chứng chỉ.
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
// Tập hợp, ngẫu nhiên, mã UUID.
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
// Đồng thời: giấy hẹn kết quả, Map đa luồng, chốt đếm ngược, bộ đếm nguyên tử.
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
// TLS.
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * KIỂM THỬ TẢI: N Client đồng thời (mỗi Client một kết nối TLS + hai thread: gửi và đọc),
 * mỗi Client lặp lại kịch bản giống người dùng thật trong D giây: xem danh sách
 * thư mục, mở danh sách thư có gắn sao, gửi thư cho một Client ngẫu nhiên (Server phải đẩy
 * EVENT NEW_MAIL tới người nhận). In ra thông lượng, độ trễ p50/p95/p99, số lỗi
 * và số EVENT nhận được.
 *
 * Chạy: scripts/loadtest.ps1 -Clients 100 -Seconds 30 (tự dựng Server riêng,
 * nới giới hạn tốc độ, dọn tài khoản load-*@test.local khi xong).
 */
public final class LoadTest {

    // Mật khẩu chung của các tài khoản thử nghiệm.
    private static final String PASSWORD = "Matkhau-Load-2026";

    // Cổng Server cần thử.
    private static int port;
    // Bộ đếm: request thành công, lỗi, thư đã gửi, EVENT NEW_MAIL đã nhận.
    private static final LongAdder requests = new LongAdder();
    private static final LongAdder errors = new LongAdder();
    private static final LongAdder sent = new LongAdder();
    private static final LongAdder eventsReceived = new LongAdder();
    // Độ trễ từng request (micro giây), danh sách đồng bộ hoá cho nhiều thread.
    private static final List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());
    // Tổng thời gian bắt tay TLS (micro giây).
    private static final AtomicLong handshakeMicros = new AtomicLong();

    // Constructor private: chỉ dùng hàm main.
    private LoadTest() {
    }

    /**
     * Tham số: [cổng] [số client] [số giây]; mặc định 5097, 50, 20.
     */
    public static void main(String[] args) throws Exception {
        port = args.length > 0 ? Integer.parseInt(args[0]) : 5097;
        int clients = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        // Nhãn riêng cho lần chạy này (để email không trùng các lần trước).
        String tag = Long.toString(System.currentTimeMillis() % 100_000_000L);
        // Email cho từng client: load-<i>-<nhãn>@test.local.
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            emails.add("load-" + i + "-" + tag + "@test.local");
        }
        System.out.printf("Kiem thu tai: %d client dong thoi, %d giay, cong %d%n", clients, seconds, port);
        boolean ok;
        try {
            ok = run(emails, seconds);
        } finally {
            // Luôn dọn tài khoản thử nghiệm, kể cả khi có lỗi.
            cleanup(tag);
        }
        // Mã thoát 0 = không lỗi.
        System.exit(ok ? 0 : 1);
    }

    /** @return true nếu không có lỗi */
    private static boolean run(List<String> emails, int seconds) throws Exception {
        List<Client> clients = new ArrayList<>();
        long setupStart = System.nanoTime();
        // Kết nối + đăng ký + đăng nhập song song (đo luôn chi phí bắt tay TLS).
        List<CompletableFuture<Client>> opening = new ArrayList<>();
        for (String email : emails) {
            opening.add(CompletableFuture.supplyAsync(() -> {
                try {
                    Client client = new Client();
                    // Đăng ký rồi đăng nhập.
                    client.call(Command.REGISTER, obj("email", email, "password", PASSWORD, "displayName", "Load"));
                    ResponseMessage login = client.call(Command.LOGIN, obj("email", email, "password", PASSWORD));
                    if (!login.isOk()) {
                        throw new IllegalStateException(login.getMessage());
                    }
                    return client;
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }));
        }
        // Chờ từng client sẵn sàng (tối đa 120 giây mỗi client).
        for (CompletableFuture<Client> future : opening) {
            clients.add(future.get(120, TimeUnit.SECONDS));
        }
        System.out.printf("Da ket noi + dang nhap %d client trong %.1f s (bat tay TLS trung binh %.1f ms)%n",
                clients.size(), (System.nanoTime() - setupStart) / 1e9,
                handshakeMicros.get() / 1000.0 / clients.size());
        // Bỏ số liệu giai đoạn chuẩn bị, chỉ đo giai đoạn chạy tải.
        requests.reset();
        errors.reset();
        latenciesMicros.clear();

        // Thời điểm kết thúc.
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        // Chốt đếm ngược: chờ mọi client chạy xong.
        CountDownLatch done = new CountDownLatch(clients.size());
        long start = System.nanoTime();
        for (int i = 0; i < clients.size(); i++) {
            Client client = clients.get(i);
            // Bộ sinh ngẫu nhiên riêng cho mỗi client (hạt giống i: chạy lại cho cùng kết quả).
            Random random = new Random(i);
            startDaemon(() -> {
                try {
                    while (System.currentTimeMillis() < deadline) {
                        // Xem danh sách thư mục.
                        timed(client, Command.GET_FOLDERS, null);
                        JsonObject mail = new JsonObject();
                        // Mở danh sách thư có gắn sao.
                        timed(client, Command.GET_MAIL_LIST, obj("view", "STARRED"));
                        // Gửi thư cho một client ngẫu nhiên.
                        String to = emails.get(random.nextInt(emails.size()));
                        JsonArray recipients = new JsonArray();
                        recipients.add(to);
                        mail.add("to", recipients);
                        mail.addProperty("subject", "Tai thu " + random.nextInt(1000));
                        mail.addProperty("body", "Noi dung kiem thu tai.");
                        if (timed(client, Command.SEND_MAIL, mail)) {
                            sent.increment();
                        }
                        Thread.sleep(50 + random.nextInt(100)); // người dùng thật không bấm liên tục
                    }
                } catch (Exception error) {
                    errors.increment();
                } finally {
                    done.countDown();
                }
            });
        }
        // Chờ mọi client xong.
        done.await();
        double elapsed = (System.nanoTime() - start) / 1e9;
        Thread.sleep(1000); // chờ EVENT cuối
        for (Client client : clients) {
            client.close();
        }

        // Sắp xếp độ trễ để tính phân vị.
        List<Long> sorted;
        synchronized (latenciesMicros) {
            sorted = new ArrayList<>(latenciesMicros);
        }
        Collections.sort(sorted);
        // In kết quả.
        System.out.println();
        System.out.println("=========== KET QUA ===========");
        System.out.printf("Request thanh cong : %d (%.0f request/giay)%n", requests.sum(), requests.sum() / elapsed);
        System.out.printf("Thu da gui         : %d%n", sent.sum());
        System.out.printf("EVENT NEW_MAIL nhan: %d%n", eventsReceived.sum());
        System.out.printf("Loi                : %d%n", errors.sum());
        System.out.printf("Do tre p50 / p95 / p99 / max: %.1f / %.1f / %.1f / %.1f ms%n",
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) / 1000.0);
        return errors.sum() == 0;
    }

    // Gửi một request, đo độ trễ, đếm thành công / lỗi.
    private static boolean timed(Client client, Command command, JsonObject payload) throws Exception {
        long begin = System.nanoTime();
        ResponseMessage response = client.call(command, payload);
        latenciesMicros.add((System.nanoTime() - begin) / 1000);
        if (response.isOk()) {
            requests.increment();
            return true;
        }
        errors.increment();
        System.err.println("[LoadTest] " + command + " loi: " + response.getMessage());
        return false;
    }

    /** Dùng thread platform daemon cho các client tải thử. */
    private static void startDaemon(Runnable task) {
        Thread thread = new Thread(task, "load-client");
        thread.setDaemon(true);
        thread.start();
    }

    // Phân vị p (vd 95) của danh sách đã sắp xếp, đổi sang mili giây.
    private static double percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        // Vị trí phần tử thứ ceil(p% * n), tính từ 0.
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, index)) / 1000.0;
    }

    // Xoá các tài khoản thử nghiệm của lần chạy này (dữ liệu liên quan bị xoá theo nhờ CASCADE).
    private static void cleanup(String tag) {
        try (java.sql.Connection connection = DatabaseConnection.getConnection();
             java.sql.PreparedStatement delete = connection.prepareStatement("DELETE FROM account WHERE email LIKE ?")) {
            delete.setString(1, "load-%-" + tag + "@test.local");
            System.out.println("Da don " + delete.executeUpdate() + " tai khoan kiem thu tai.");
        } catch (java.sql.SQLException error) {
            System.err.println("Khong don duoc du lieu kiem thu: " + error.getMessage());
        }
    }

    // Tạo JsonObject từ các cặp khoá, giá trị: obj("a", "1", "b", "2").
    private static JsonObject obj(String... keyValues) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            object.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return object;
    }

    /** Một Client tối giản: một socket TLS, một thread đọc frame, khớp RESPONSE theo requestId. */
    private static final class Client implements AutoCloseable {
        // Socket TLS tới Server.
        private final SSLSocket socket;
        // Luồng ghi UTF-8.
        private final Writer out;
        // requestId -> giấy hẹn chờ câu trả lời.
        private final Map<String, CompletableFuture<ResponseMessage>> pending = new ConcurrentHashMap<>();

        // Kết nối TLS và khởi động thread đọc.
        Client() throws Exception {
            // TLS 1.3, tin mọi chứng chỉ (chỉ dùng cho kiểm thử trên máy mình, không dùng trong ứng dụng thật).
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String type) { }
                public void checkServerTrusted(X509Certificate[] chain, String type) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            // Đo thời gian kết nối + bắt tay.
            long begin = System.nanoTime();
            socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", port);
            socket.startHandshake();
            handshakeMicros.addAndGet((System.nanoTime() - begin) / 1000);
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            // Bộ đọc frame (giới hạn nới gấp 4 cho kiểm thử).
            FrameReader reader = new FrameReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    4 * ProtocolConstants.MAX_FRAME_CHARS);
            // Thread đọc: đếm EVENT NEW_MAIL, giao RESPONSE cho đúng giấy hẹn.
            startDaemon(() -> {
                try {
                    String frame;
                    while ((frame = reader.readFrame()) != null) {
                        if (ProtocolUtil.peekType(frame) == MessageType.EVENT) {
                            if ("NEW_MAIL".equals(ProtocolUtil.fromJson(frame, EventMessage.class).getEventName())) {
                                eventsReceived.increment();
                            }
                            continue;
                        }
                        ResponseMessage response = ProtocolUtil.fromJson(frame, ResponseMessage.class);
                        CompletableFuture<ResponseMessage> future = pending.remove(response.getRequestId());
                        if (future != null) {
                            future.complete(response);
                        }
                    }
                } catch (IOException ignored) {
                    // Kết nối đóng: thread kết thúc.
                }
            });
        }

        // Gửi request và chờ câu trả lời (tối đa 30 giây).
        ResponseMessage call(Command command, JsonObject payload) throws Exception {
            String id = UUID.randomUUID().toString();
            CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
            // Đăng ký trước khi gửi (câu trả lời có thể về rất nhanh).
            pending.put(id, future);
            // Khoá luồng ghi: nhiều thread không ghi xen vào một frame.
            synchronized (out) {
                ProtocolUtil.writeFrame(out, ProtocolUtil.toJson(new RequestMessage(id, command, payload)));
            }
            return future.get(30, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
