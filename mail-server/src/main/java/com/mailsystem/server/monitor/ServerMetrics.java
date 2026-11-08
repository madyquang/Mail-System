// Gói "monitor": số liệu vận hành và trang giám sát.
package com.mailsystem.server.monitor;

// Command: danh sách lệnh, để đếm số lần gọi mỗi lệnh.
import com.mailsystem.common.protocol.Command;

// EnumMap: Map có khoá là enum (gọn và nhanh).
import java.util.EnumMap;
// LinkedHashMap: Map giữ thứ tự chèn (JSON ra đúng thứ tự).
import java.util.LinkedHashMap;
import java.util.Map;
// AtomicLong / AtomicLongArray: số / mảng số cập nhật nguyên tử không cần khoá.
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
// LongAdder: bộ đếm cộng rất nhanh khi nhiều thread cùng cộng.
import java.util.concurrent.atomic.LongAdder;

/**
 * Số liệu vận hành cho bảng giám sát. Được cập nhật từ rất nhiều thread cùng
 * lúc nên dùng LongAdder/AtomicLong (cộng không khoá) thay vì synchronized.
 *
 * Tốc độ request: vòng 60 ô, mỗi ô là một giây (ô = giây % 60). Ô cũ được xoá
 * khi giây đó quay lại, nên luôn có biểu đồ 60 giây gần nhất mà không cấp phát gì thêm.
 */
public final class ServerMetrics {

    // Đối tượng duy nhất.
    private static final ServerMetrics INSTANCE = new ServerMetrics();
    // Biểu đồ tốc độ giữ 60 giây gần nhất.
    private static final int WINDOW_SECONDS = 60;

    // Thời điểm Server bắt đầu (để tính thời gian chạy).
    private final long startedAt = System.currentTimeMillis();
    // Các bộ đếm tổng: số request, số lỗi, số lần bị giới hạn tốc độ, byte vào/ra,
    // số EVENT đã đẩy, số kết nối được nhận / bị từ chối.
    private final LongAdder requests = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder eventsPushed = new LongAdder();
    private final LongAdder connectionsAccepted = new LongAdder();
    private final LongAdder connectionsRejected = new LongAdder();
    // Số lần gọi theo từng lệnh.
    private final Map<Command, LongAdder> perCommand = new EnumMap<>(Command.class);
    // Số request trong từng ô giây.
    private final AtomicLongArray perSecond = new AtomicLongArray(WINDOW_SECONDS);
    // Ô đó đang chứa số liệu của giây nào (để biết ô đã cũ chưa).
    private final AtomicLongArray secondStamp = new AtomicLongArray(WINDOW_SECONDS);
    // Tổng thời gian xử lý (micro giây) để tính trung bình.
    private final AtomicLong totalLatencyMicros = new AtomicLong();

    // Constructor private (singleton): tạo sẵn bộ đếm cho mọi lệnh.
    private ServerMetrics() {
        for (Command command : Command.values()) {
            perCommand.put(command, new LongAdder());
        }
    }

    /** @return đối tượng ServerMetrics duy nhất */
    public static ServerMetrics get() {
        return INSTANCE;
    }

    /**
     * Ghi nhận một request đã xử lý xong.
     *
     * @param command      lệnh (null nếu không đọc được lệnh)
     * @param latencyNanos thời gian xử lý (nano giây)
     * @param ok           thành công hay lỗi
     */
    public void recordRequest(Command command, long latencyNanos, boolean ok) {
        // Tăng tổng số request.
        requests.increment();
        // Tăng bộ đếm của lệnh.
        if (command != null) {
            perCommand.get(command).increment();
        }
        // Lỗi thì tăng bộ đếm lỗi.
        if (!ok) {
            errors.increment();
        }
        // Cộng thời gian xử lý (đổi nano giây sang micro giây).
        totalLatencyMicros.addAndGet(latencyNanos / 1_000);
        // Giây hiện tại và ô tương ứng trong vòng 60 ô.
        long second = System.currentTimeMillis() / 1000;
        int slot = (int) (second % WINDOW_SECONDS);
        // Ô đang chứa số liệu của giây cũ (60 giây trước): đặt lại về 0 cho giây mới.
        if (secondStamp.get(slot) != second) {
            secondStamp.set(slot, second);
            perSecond.set(slot, 0);
        }
        // Tăng số request của giây này.
        perSecond.incrementAndGet(slot);
    }

    /** Ghi nhận một request bị từ chối vì gửi quá nhanh. */
    public void recordRateLimited() {
        rateLimited.increment();
    }

    /** Cộng số byte nhận vào. */
    public void addBytesIn(long bytes) {
        bytesIn.add(bytes);
    }

    /** Cộng số byte gửi ra. */
    public void addBytesOut(long bytes) {
        bytesOut.add(bytes);
    }

    /** Ghi nhận một EVENT đã đẩy về Client. */
    public void recordEventPushed() {
        eventsPushed.increment();
    }

    /** Ghi nhận một kết nối mới: được nhận (true) hoặc bị từ chối vì quá tải (false). */
    public void recordConnection(boolean accepted) {
        // Chọn bộ đếm theo kết quả rồi tăng.
        (accepted ? connectionsAccepted : connectionsRejected).increment();
    }

    /** Ảnh chụp số liệu (để chuyển thành JSON). */
    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        // Tổng số request (dùng lại để tính trung bình).
        long total = requests.sum();
        // Các số tổng.
        data.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        data.put("requests", total);
        data.put("errors", errors.sum());
        data.put("rateLimited", rateLimited.sum());
        data.put("bytesIn", bytesIn.sum());
        data.put("bytesOut", bytesOut.sum());
        data.put("eventsPushed", eventsPushed.sum());
        data.put("connectionsAccepted", connectionsAccepted.sum());
        data.put("connectionsRejected", connectionsRejected.sum());
        // Thời gian xử lý trung bình (ms, làm tròn 3 chữ số thập phân); chưa có request thì 0.
        data.put("avgLatencyMs", total == 0 ? 0 : Math.round(totalLatencyMicros.get() / (double) total) / 1000.0);

        // Dựng chuỗi 60 giá trị, từ 59 giây trước tới giây hiện tại.
        long now = System.currentTimeMillis() / 1000;
        long[] series = new long[WINDOW_SECONDS];
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            // Giây ứng với vị trí i.
            long second = now - (WINDOW_SECONDS - 1 - i);
            int slot = (int) (second % WINDOW_SECONDS);
            // Ô đúng giây đó thì lấy số liệu, ô cũ (không có request giây đó) thì 0.
            series[i] = secondStamp.get(slot) == second ? perSecond.get(slot) : 0;
        }
        data.put("requestsPerSecond", series);

        // Số lần gọi theo lệnh: chỉ lệnh đã được gọi, nhiều nhất xếp trước.
        Map<String, Long> commands = new LinkedHashMap<>();
        perCommand.entrySet().stream()
                .filter(entry -> entry.getValue().sum() > 0)
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(entry -> commands.put(entry.getKey().name(), entry.getValue().sum()));
        data.put("commands", commands);
        return data;
    }
}
