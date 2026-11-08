// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// Clock / Duration / Instant: nguồn thời gian, khoảng thời gian, thời điểm.
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
// Locale.ROOT: đổi chữ thường không phụ thuộc ngôn ngữ máy.
import java.util.Locale;
// Map / ConcurrentHashMap: bộ nhớ đệm bộ đếm, an toàn đa luồng.
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chống dò mật khẩu (brute force): sau maxFailures lần sai liên tiếp trong
 * cửa sổ thời gian, khoá tạm theo CẢ email lẫn địa chỉ IP.
 *
 * Khoá theo email chặn kẻ thử nhiều mật khẩu cho một tài khoản (kể cả từ nhiều
 * IP); khoá theo IP chặn kẻ thử một mật khẩu cho nhiều tài khoản. Khoá áp dụng
 * như nhau với email có hay không tồn tại, nên không lộ email nào đã đăng ký.
 *
 * Bộ đếm được ghi xuống ThrottleStore (bảng login_attempt) nên khởi động lại
 * Server không giúp kẻ tấn công "xoá án". Bộ nhớ trong chỉ là cache.
 */
public class LoginThrottle {

    // Số lần sai tối đa theo email (theo IP là gấp 4).
    private final int maxFailures;
    // Thời gian khoá, cũng là độ dài cửa sổ đếm lần sai.
    private final Duration lockDuration;
    // Nguồn thời gian.
    private final Clock clock;
    // Nơi lưu bền bộ đếm.
    private final ThrottleStore store;
    // Cache trong bộ nhớ: khoá -> bộ đếm.
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /** Tạo với bộ lưu trong bộ nhớ (không bền). */
    public LoginThrottle(int maxFailures, Duration lockDuration) {
        this(maxFailures, lockDuration, Clock.systemUTC(), ThrottleStore.inMemory());
    }

    /** Tạo với bộ lưu cho trước (Server dùng JdbcThrottleStore). */
    public LoginThrottle(int maxFailures, Duration lockDuration, ThrottleStore store) {
        this(maxFailures, lockDuration, Clock.systemUTC(), store);
    }

    // Dùng cho unit test: đồng hồ giả, lưu trong bộ nhớ.
    LoginThrottle(int maxFailures, Duration lockDuration, Clock clock) {
        this(maxFailures, lockDuration, clock, ThrottleStore.inMemory());
    }

    // Constructor đầy đủ mà các constructor trên đều gọi tới.
    LoginThrottle(int maxFailures, Duration lockDuration, Clock clock, ThrottleStore store) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.clock = clock;
        this.store = store;
    }

    /** @return thời gian còn bị khoá, hoặc Duration.ZERO nếu được phép thử */
    public Duration lockRemaining(String email, String ip) {
        // Thời gian còn khoá theo email và theo IP.
        Duration byEmail = remaining(key("email", email));
        Duration byIp = remaining(key("ip", ip));
        // Lấy cái lâu hơn.
        return byEmail.compareTo(byIp) >= 0 ? byEmail : byIp;
    }

    /** @return true nếu lần sai này làm tài khoản/IP bị khoá */
    public boolean recordFailure(String email, String ip) {
        // Tăng bộ đếm theo email, giới hạn maxFailures.
        boolean lockedEmail = increment(key("email", email), maxFailures);
        // IP được phép sai nhiều hơn (nhiều người dùng chung một mạng LAN/NAT).
        boolean lockedIp = increment(key("ip", ip), maxFailures * 4);
        // Bị khoá theo một trong hai.
        return lockedEmail || lockedIp;
    }

    /** Đăng nhập đúng: xoá bộ đếm của cả email lẫn IP. */
    public void recordSuccess(String email, String ip) {
        for (String key : new String[] {key("email", email), key("ip", ip)}) {
            // Có trong cache hoặc có trong CSDL thì xoá ở CSDL (tránh câu DELETE thừa khi không có gì).
            if (attempts.remove(key) != null || store.load(key).isPresent()) {
                store.delete(key);
            }
        }
    }

    // Tăng bộ đếm của một khoá; trả true nếu khoá đang bị khoá sau lần tăng này.
    private boolean increment(String key, int limit) {
        Instant now = clock.instant();
        // compute chạy nguyên tử trên khoá (nhiều lần thử cùng lúc không đếm sai).
        Attempts updated = attempts.compute(key, (k, current) -> {
            // Chưa có trong cache thì nạp từ CSDL.
            Attempts value = current == null ? loadFromStore(k) : current;
            // Chưa từng sai, hoặc bộ đếm cũ đã hết hiệu lực: bắt đầu đếm lại.
            if (value == null || value.isStale(now, lockDuration)) {
                value = new Attempts();
            }
            // Tăng số lần sai và ghi thời điểm.
            value.failures++;
            value.lastFailure = now;
            // Đủ giới hạn thì khoá tới now + thời gian khoá.
            if (value.failures >= limit) {
                value.lockedUntil = now.plus(lockDuration);
            }
            return value;
        });
        // Ghi bền xuống CSDL.
        store.save(key, new ThrottleStore.Record(updated.failures, updated.lastFailure, updated.lockedUntil));
        // Đang bị khoá nếu thời điểm hết khoá nằm sau hiện tại.
        return updated.lockedUntil != null && updated.lockedUntil.isAfter(now);
    }

    // Thời gian còn bị khoá của một khoá (ZERO nếu không khoá).
    private Duration remaining(String key) {
        // Lấy từ cache; chưa có thì nạp từ CSDL (null nếu CSDL cũng không có).
        Attempts value = attempts.computeIfAbsent(key, this::loadFromStore);
        if (value == null || value.lockedUntil == null) {
            return Duration.ZERO;
        }
        // Khoảng từ bây giờ tới lúc hết khoá.
        Duration left = Duration.between(clock.instant(), value.lockedUntil);
        // Đã hết khoá.
        if (left.isNegative() || left.isZero()) {
            return Duration.ZERO;
        }
        return left;
    }

    // Nạp bộ đếm từ CSDL thành đối tượng Attempts (null nếu không có).
    private Attempts loadFromStore(String key) {
        return store.load(key).map(record -> {
            Attempts value = new Attempts();
            value.failures = record.failures();
            value.lastFailure = record.lastFailure();
            value.lockedUntil = record.lockedUntil();
            return value;
        }).orElse(null);
    }

    // Tạo khoá dạng "email:an@mail.local" hoặc "ip:192.168.1.5" (chữ thường).
    private static String key(String kind, String value) {
        return kind + ":" + (value == null ? "" : value.toLowerCase(Locale.ROOT));
    }

    // Bộ đếm của một khoá trong bộ nhớ.
    private static final class Attempts {
        // Số lần sai liên tiếp.
        int failures;
        // Lần sai gần nhất.
        Instant lastFailure;
        // Bị khoá tới lúc nào (null = không khoá).
        Instant lockedUntil;

        // Bộ đếm hết hiệu lực khi: đã từng khoá và khoá đã hết, hoặc lần sai cuối đã quá cửa sổ.
        boolean isStale(Instant now, Duration window) {
            boolean lockExpired = lockedUntil != null && !lockedUntil.isAfter(now);
            boolean windowExpired = lastFailure != null && lastFailure.plus(window).isBefore(now);
            return lockExpired || windowExpired;
        }
    }
}
