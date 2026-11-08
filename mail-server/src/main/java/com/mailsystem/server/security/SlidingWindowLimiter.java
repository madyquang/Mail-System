// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// Clock: nguồn thời gian (thay được trong test).
import java.time.Clock;
// Duration: độ dài cửa sổ thời gian.
import java.time.Duration;
// Instant: một thời điểm.
import java.time.Instant;
// ArrayDeque/Deque: hàng đợi hai đầu (thêm cuối, lấy đầu).
import java.util.ArrayDeque;
import java.util.Deque;
// Map / ConcurrentHashMap: bảng khoá -> hàng đợi, an toàn đa luồng.
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Giới hạn số sự kiện theo khoá trong một cửa sổ trượt (VD: số lần đăng ký mỗi IP mỗi giờ). */
public class SlidingWindowLimiter {

    // Số sự kiện tối đa trong một cửa sổ.
    private final int limit;
    // Độ dài cửa sổ (vd 1 giờ).
    private final Duration window;
    // Nguồn thời gian.
    private final Clock clock;
    // khoá (vd IP) -> các thời điểm xảy ra sự kiện còn nằm trong cửa sổ, cũ nhất ở đầu.
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    /**
     * Tạo bộ giới hạn dùng đồng hồ hệ thống.
     *
     * @param limit  số sự kiện tối đa
     * @param window độ dài cửa sổ
     */
    public SlidingWindowLimiter(int limit, Duration window) {
        // Gọi constructor bên dưới với đồng hồ UTC của hệ thống.
        this(limit, window, Clock.systemUTC());
    }

    // Constructor nhận đồng hồ từ ngoài (unit test dùng đồng hồ giả).
    SlidingWindowLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** Ghi nhận một sự kiện nếu chưa vượt giới hạn. */
    public boolean tryAcquire(String key) {
        // Thời điểm hiện tại.
        Instant now = clock.instant();
        // Mảng 1 phần tử để lambda bên dưới gán được kết quả (biến thường trong lambda phải là final).
        boolean[] allowed = {false};
        // compute chạy nguyên tử trên khoá: an toàn khi nhiều kết nối cùng IP gọi một lúc.
        events.compute(key, (k, queue) -> {
            // Chưa có hàng đợi thì tạo mới.
            Deque<Instant> value = queue == null ? new ArrayDeque<>() : queue;
            // Bỏ các sự kiện đã nằm ngoài cửa sổ (không sau mốc now - window).
            while (!value.isEmpty() && !value.peekFirst().isAfter(now.minus(window))) {
                value.pollFirst();
            }
            // Còn chỗ: ghi nhận sự kiện mới và cho phép.
            if (value.size() < limit) {
                value.addLast(now);
                allowed[0] = true;
            }
            // Hàng đợi rỗng thì trả null để Map xoá khoá (tiết kiệm bộ nhớ).
            return value.isEmpty() ? null : value;
        });
        // Kết quả: được phép hay không.
        return allowed[0];
    }
}
