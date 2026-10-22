package com.mailsystem.server.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token tiếp tục phiên (session resumption) để Client tự đăng nhập lại sau
 * khi rớt mạng mà KHÔNG phải giữ mật khẩu trong bộ nhớ.
 *
 * - Token = 256 bit ngẫu nhiên; Server chỉ lưu SHA-256 của token (lộ bộ nhớ
 *   Server cũng không dùng lại được token).
 * - Dùng một lần: mỗi lần RESUME_SESSION thành công, token cũ bị huỷ và Client
 *   nhận token mới (rotation) — token bị đánh cắp và dùng trước sẽ làm token
 *   thật hết hiệu lực, Client thật phải đăng nhập lại.
 * - Huỷ toàn bộ khi đổi mật khẩu hoặc "đăng xuất mọi thiết bị".
 */
public final class ResumeTokens {

    private static final ResumeTokens INSTANCE = new ResumeTokens(Clock.systemUTC());
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(int accountId, Instant expiresAt) {
    }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    ResumeTokens(Clock clock) {
        this.clock = clock;
    }

    public static ResumeTokens get() {
        return INSTANCE;
    }

    /** Cấp token mới; trả về (token gửi cho Client, hash để kết nối nhớ mà huỷ khi LOGOUT). */
    public Issued issue(int accountId, Duration ttl) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = hash(token);
        tokens.put(hash, new Entry(accountId, clock.instant().plus(ttl)));
        purgeExpired();
        return new Issued(token, hash);
    }

    public record Issued(String token, String hash) {
    }

    /** Dùng token (một lần). @return accountId, hoặc -1 nếu không hợp lệ / hết hạn. */
    public int redeem(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            return -1;
        }
        Entry entry = tokens.remove(hash(token));
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            return -1;
        }
        return entry.accountId();
    }

    public void revoke(String hash) {
        if (hash != null) {
            tokens.remove(hash);
        }
    }

    public void revokeAll(int accountId) {
        tokens.values().removeIf(entry -> entry.accountId() == accountId);
    }

    public int size() {
        return tokens.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        tokens.values().removeIf(entry -> entry.expiresAt().isBefore(now));
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
