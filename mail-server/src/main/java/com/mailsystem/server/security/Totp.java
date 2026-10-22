package com.mailsystem.server.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã một lần theo thời gian (TOTP, RFC 6238) — chuẩn mà Google Authenticator,
 * Microsoft Authenticator dùng: mã = HMAC-SHA1(khoá bí mật, số bước 30 giây)
 * rút gọn thành 6 chữ số. Server và điện thoại cùng biết khoá bí mật và cùng
 * xem đồng hồ nên tính ra cùng một mã, không cần mạng giữa hai bên.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** Khoá bí mật 160 bit, mã hoá Base32 (định dạng các ứng dụng xác thực yêu cầu). */
    public static String newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return base32(secret);
    }

    /** URI để tạo mã QR: otpauth://totp/MailSystem:email?secret=...&issuer=MailSystem */
    public static String otpauthUri(String email, String secret) {
        String label = "MailSystem:" + email;
        return "otpauth://totp/" + urlEncode(label) + "?secret=" + secret + "&issuer=MailSystem&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /**
     * Kiểm tra mã, chấp nhận lệch ±1 bước (đồng hồ điện thoại lệch tới 30 giây).
     *
     * @return số bước khớp (để chống dùng lại cùng một mã), hoặc -1 nếu sai
     */
    public static long verify(String secret, String code, long epochSeconds) {
        if (secret == null || code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long current = epochSeconds / STEP_SECONDS;
        for (long step = current - 1; step <= current + 1; step++) {
            if (constantTimeEquals(code(secret, step), code)) {
                return step;
            }
        }
        return -1;
    }

    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] base32Decode(String text) {
        String clean = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Ký tự Base32 không hợp lệ");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
