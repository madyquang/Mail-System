// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// ByteBuffer: đổi số long thành 8 byte; gom byte khi giải mã Base32.
import java.nio.ByteBuffer;
// Lỗi chung của các thao tác mật mã.
import java.security.GeneralSecurityException;
// SecureRandom: sinh khoá bí mật ngẫu nhiên.
import java.security.SecureRandom;
// Locale.ROOT: đổi chữ hoa không phụ thuộc ngôn ngữ máy.
import java.util.Locale;
// Mac: tính HMAC (mã xác thực thông điệp có khoá).
import javax.crypto.Mac;
// SecretKeySpec: bọc mảng byte thành khoá cho Mac.
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã một lần theo thời gian (TOTP, RFC 6238) — chuẩn mà Google Authenticator,
 * Microsoft Authenticator dùng: mã = HMAC-SHA1(khoá bí mật, số bước 30 giây)
 * rút gọn thành 6 chữ số. Server và điện thoại cùng biết khoá bí mật và cùng
 * xem đồng hồ nên tính ra cùng một mã, không cần mạng giữa hai bên.
 */
public final class Totp {

    // Số chữ số của mã.
    public static final int DIGITS = 6;
    // Mỗi mã có hiệu lực trong một bước 30 giây.
    public static final int STEP_SECONDS = 30;
    // Bảng 32 ký tự của mã hoá Base32 (RFC 4648).
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    // Bộ sinh ngẫu nhiên dùng chung.
    private static final SecureRandom RANDOM = new SecureRandom();

    // Constructor private: lớp chỉ có hàm tĩnh.
    private Totp() {
    }

    /** Khoá bí mật 160 bit, mã hoá Base32 (định dạng các ứng dụng xác thực yêu cầu). */
    public static String newSecret() {
        // 20 byte = 160 bit ngẫu nhiên.
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        // Mã hoá Base32 để người dùng/ứng dụng nhập được.
        return base32(secret);
    }

    /** URI để tạo mã QR: otpauth://totp/MailSystem:email?secret=...&issuer=MailSystem */
    public static String otpauthUri(String email, String secret) {
        // Nhãn hiện trong ứng dụng xác thực.
        String label = "MailSystem:" + email;
        // Ghép URI theo định dạng chuẩn "Key Uri Format" mà ứng dụng xác thực đọc được.
        return "otpauth://totp/" + urlEncode(label) + "?secret=" + secret + "&issuer=MailSystem&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /**
     * Kiểm tra mã, chấp nhận lệch ±1 bước (đồng hồ điện thoại lệch tới 30 giây).
     *
     * @return số bước khớp (để chống dùng lại cùng một mã), hoặc -1 nếu sai
     */
    public static long verify(String secret, String code, long epochSeconds) {
        // Thiếu dữ liệu, hoặc mã không phải đúng 6 chữ số.
        if (secret == null || code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        // Số bước 30 giây tính từ 1/1/1970.
        long current = epochSeconds / STEP_SECONDS;
        // Thử bước trước, bước hiện tại và bước sau.
        for (long step = current - 1; step <= current + 1; step++) {
            if (constantTimeEquals(code(secret, step), code)) {
                return step;
            }
        }
        // Không khớp bước nào.
        return -1;
    }

    /**
     * Tính mã 6 số cho một bước thời gian (thuật toán HOTP của RFC 4226).
     *
     * @param secret khoá bí mật Base32
     * @param step   số bước 30 giây
     * @return chuỗi 6 chữ số (có số 0 ở đầu nếu cần)
     */
    public static String code(String secret, long step) {
        try {
            // Bộ tính HMAC-SHA1 với khoá đã giải mã Base32.
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            // HMAC của số bước (8 byte, thứ tự big-endian) -> 20 byte.
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            // "Dynamic truncation": 4 bit thấp của byte cuối chọn vị trí bắt đầu (0..15).
            int offset = hash[hash.length - 1] & 0x0F;
            // Ghép 4 byte từ vị trí đó thành số 31 bit (bỏ bit dấu bằng & 0x7F).
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            // Lấy 6 chữ số cuối, thêm số 0 ở đầu cho đủ 6 ký tự.
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    // Mã hoá byte thành Base32 (mỗi 5 bit thành một ký tự).
    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        // Bộ đệm bit và số bit đang chờ trong bộ đệm.
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            // Đẩy 8 bit của byte vào bộ đệm.
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            // Còn đủ 5 bit thì lấy ra một ký tự.
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        // Còn dư dưới 5 bit: đệm 0 bên phải cho đủ một ký tự.
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    // Giải mã Base32 thành byte.
    static byte[] base32Decode(String text) {
        // Bỏ dấu '=' đệm và khoảng trắng, đổi chữ hoa.
        String clean = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        // Mỗi ký tự 5 bit -> tổng số byte = số ký tự * 5 / 8.
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            // Giá trị 0..31 của ký tự.
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Ký tự Base32 không hợp lệ");
            }
            // Đẩy 5 bit vào bộ đệm.
            buffer = (buffer << 5) | value;
            bits += 5;
            // Đủ 8 bit thì lấy ra một byte.
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }

    // So sánh hai chuỗi trong thời gian hằng (không lộ vị trí khác nhau qua thời gian).
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    // Mã hoá URL; đổi '+' (khoảng trắng kiểu form) thành "%20".
    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
