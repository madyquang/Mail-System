// Gói "session": quản lý các phiên phía Server.
package com.mailsystem.server.session;

// StandardCharsets: bảng mã (US_ASCII) để đổi token thành byte.
import java.nio.charset.StandardCharsets;
// MessageDigest: tính SHA-256.
import java.security.MessageDigest;
// Lỗi khi máy không hỗ trợ thuật toán băm.
import java.security.NoSuchAlgorithmException;
// SecureRandom: sinh số ngẫu nhiên an toàn cho mật mã.
import java.security.SecureRandom;
// Clock / Duration / Instant: nguồn thời gian, khoảng thời gian, thời điểm.
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
// Base64: mã hoá byte thành chữ.
import java.util.Base64;
// Map / ConcurrentHashMap: bảng hash -> thông tin token, an toàn đa luồng.
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

    // Đối tượng duy nhất, dùng đồng hồ UTC của hệ thống.
    private static final ResumeTokens INSTANCE = new ResumeTokens(Clock.systemUTC());
    // Bộ sinh ngẫu nhiên dùng chung.
    private static final SecureRandom RANDOM = new SecureRandom();

    // Thông tin lưu cho mỗi token: thuộc tài khoản nào, hết hạn lúc nào.
    private record Entry(int accountId, Instant expiresAt) {
    }

    // Hash của token -> thông tin (chỉ lưu hash, không lưu token gốc).
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    // Nguồn thời gian (test truyền đồng hồ giả).
    private final Clock clock;

    // Constructor không public: dùng get(); test trong cùng gói tạo riêng với đồng hồ giả.
    ResumeTokens(Clock clock) {
        this.clock = clock;
    }

    /** @return đối tượng ResumeTokens duy nhất */
    public static ResumeTokens get() {
        return INSTANCE;
    }

    /** Cấp token mới; trả về (token gửi cho Client, hash để kết nối nhớ mà huỷ khi LOGOUT). */
    public Issued issue(int accountId, Duration ttl) {
        // 32 byte = 256 bit ngẫu nhiên.
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        // Mã hoá Base64 dạng an toàn cho URL, bỏ dấu '=' thừa ở cuối.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Băm token để lưu.
        String hash = hash(token);
        // Lưu: hash -> (tài khoản, thời điểm hết hạn = bây giờ + ttl).
        tokens.put(hash, new Entry(accountId, clock.instant().plus(ttl)));
        // Tiện dọn các token đã hết hạn.
        purgeExpired();
        // Trả token gốc (gửi Client) và hash (kết nối giữ lại).
        return new Issued(token, hash);
    }

    /**
     * Kết quả cấp token.
     *
     * @param token token gốc gửi cho Client
     * @param hash  SHA-256 của token (Server giữ)
     */
    public record Issued(String token, String hash) {
    }

    /** Dùng token (một lần). @return accountId, hoặc -1 nếu không hợp lệ / hết hạn. */
    public int redeem(String token) {
        // Độ dài bất thường thì từ chối ngay (token thật dài 43 ký tự).
        if (token == null || token.length() < 20 || token.length() > 100) {
            return -1;
        }
        // remove: lấy ra và xoá luôn, nên token chỉ dùng được một lần.
        Entry entry = tokens.remove(hash(token));
        // Không có, hoặc đã hết hạn.
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            return -1;
        }
        // Hợp lệ: trả tài khoản sở hữu.
        return entry.accountId();
    }

    /** Huỷ một token theo hash (khi LOGOUT). */
    public void revoke(String hash) {
        if (hash != null) {
            tokens.remove(hash);
        }
    }

    /** Huỷ mọi token của một tài khoản (đổi mật khẩu, đăng xuất mọi nơi). */
    public void revokeAll(int accountId) {
        tokens.values().removeIf(entry -> entry.accountId() == accountId);
    }

    /** @return số token đang lưu (cho trang giám sát) */
    public int size() {
        return tokens.size();
    }

    // Xoá các token đã quá hạn.
    private void purgeExpired() {
        Instant now = clock.instant();
        tokens.values().removeIf(entry -> entry.expiresAt().isBefore(now));
    }

    // SHA-256 của token, mã hoá Base64.
    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            // Không xảy ra trên Java chuẩn (luôn có SHA-256).
            throw new IllegalStateException(error);
        }
    }
}
