package com.mailsystem.server.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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

    private final int maxFailures;
    private final Duration lockDuration;
    private final Clock clock;
    private final ThrottleStore store;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginThrottle(int maxFailures, Duration lockDuration) {
        this(maxFailures, lockDuration, Clock.systemUTC(), ThrottleStore.inMemory());
    }

    public LoginThrottle(int maxFailures, Duration lockDuration, ThrottleStore store) {
        this(maxFailures, lockDuration, Clock.systemUTC(), store);
    }

    LoginThrottle(int maxFailures, Duration lockDuration, Clock clock) {
        this(maxFailures, lockDuration, clock, ThrottleStore.inMemory());
    }

    LoginThrottle(int maxFailures, Duration lockDuration, Clock clock, ThrottleStore store) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.clock = clock;
        this.store = store;
    }

    /** @return thời gian còn bị khoá, hoặc Duration.ZERO nếu được phép thử */
    public Duration lockRemaining(String email, String ip) {
        Duration byEmail = remaining(key("email", email));
        Duration byIp = remaining(key("ip", ip));
        return byEmail.compareTo(byIp) >= 0 ? byEmail : byIp;
    }

    /** @return true nếu lần sai này làm tài khoản/IP bị khoá */
    public boolean recordFailure(String email, String ip) {
        boolean lockedEmail = increment(key("email", email), maxFailures);
        // IP được phép sai nhiều hơn (nhiều người dùng chung một mạng LAN/NAT).
        boolean lockedIp = increment(key("ip", ip), maxFailures * 4);
        return lockedEmail || lockedIp;
    }

    public void recordSuccess(String email, String ip) {
        for (String key : new String[] {key("email", email), key("ip", ip)}) {
            if (attempts.remove(key) != null || store.load(key).isPresent()) {
                store.delete(key);
            }
        }
    }

    private boolean increment(String key, int limit) {
        Instant now = clock.instant();
        Attempts updated = attempts.compute(key, (k, current) -> {
            Attempts value = current == null ? loadFromStore(k) : current;
            if (value == null || value.isStale(now, lockDuration)) {
                value = new Attempts();
            }
            value.failures++;
            value.lastFailure = now;
            if (value.failures >= limit) {
                value.lockedUntil = now.plus(lockDuration);
            }
            return value;
        });
        store.save(key, new ThrottleStore.Record(updated.failures, updated.lastFailure, updated.lockedUntil));
        return updated.lockedUntil != null && updated.lockedUntil.isAfter(now);
    }

    private Duration remaining(String key) {
        Attempts value = attempts.computeIfAbsent(key, this::loadFromStore);
        if (value == null || value.lockedUntil == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), value.lockedUntil);
        if (left.isNegative() || left.isZero()) {
            return Duration.ZERO;
        }
        return left;
    }

    private Attempts loadFromStore(String key) {
        return store.load(key).map(record -> {
            Attempts value = new Attempts();
            value.failures = record.failures();
            value.lastFailure = record.lastFailure();
            value.lockedUntil = record.lockedUntil();
            return value;
        }).orElse(null);
    }

    private static String key(String kind, String value) {
        return kind + ":" + (value == null ? "" : value.toLowerCase(Locale.ROOT));
    }

    private static final class Attempts {
        int failures;
        Instant lastFailure;
        Instant lockedUntil;

        boolean isStale(Instant now, Duration window) {
            boolean lockExpired = lockedUntil != null && !lockedUntil.isAfter(now);
            boolean windowExpired = lastFailure != null && lastFailure.plus(window).isBefore(now);
            return lockExpired || windowExpired;
        }
    }
}
