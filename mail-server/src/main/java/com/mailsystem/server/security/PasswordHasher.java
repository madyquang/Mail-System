// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// Lỗi chung của các thao tác mật mã.
import java.security.GeneralSecurityException;
// MessageDigest.isEqual: so sánh hai mảng byte trong thời gian hằng.
import java.security.MessageDigest;
// SecureRandom: bộ sinh số ngẫu nhiên an toàn cho mật mã (sinh salt).
import java.security.SecureRandom;
// Arrays.fill: ghi đè mảng ký tự mật khẩu sau khi dùng.
import java.util.Arrays;
// Base64: mã hoá byte thành chữ để lưu vào CSDL.
import java.util.Base64;
// SecretKeyFactory: bộ tạo khoá theo thuật toán PBKDF2.
import javax.crypto.SecretKeyFactory;
// PBEKeySpec: tham số PBKDF2 (mật khẩu, salt, số vòng, độ dài).
import javax.crypto.spec.PBEKeySpec;

/**
 * Băm mật khẩu bằng PBKDF2-HMAC-SHA256 với salt ngẫu nhiên.
 * Định dạng lưu: pbkdf2_sha256$&lt;vòng lặp&gt;$&lt;salt base64&gt;$&lt;hash base64&gt;.
 */
public final class PasswordHasher {

    // Số vòng lặp: càng lớn thì mỗi lần thử mật khẩu càng chậm, kẻ dò mật khẩu càng khó.
    private static final int ITERATIONS = 210_000;
    // Salt 16 byte ngẫu nhiên cho mỗi mật khẩu.
    private static final int SALT_BYTES = 16;
    // Độ dài kết quả băm: 256 bit (32 byte).
    private static final int HASH_BITS = 256;
    // Một bộ sinh ngẫu nhiên dùng chung.
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hash giả để vẫn tốn thời gian băm khi email không tồn tại (chống dò email theo thời gian). */
    private static final String DUMMY_HASH;

    // Khối static: chạy một lần khi lớp được nạp, tính sẵn DUMMY_HASH.
    static {
        try {
            DUMMY_HASH = hash("dummy-password-for-timing");
        } catch (GeneralSecurityException error) {
            // Máy không hỗ trợ PBKDF2: lớp không dùng được, báo lỗi khởi tạo.
            throw new ExceptionInInitializerError(error);
        }
    }

    // Constructor private: lớp chỉ có hàm tĩnh.
    private PasswordHasher() {
    }

    /**
     * Băm mật khẩu để lưu vào CSDL.
     *
     * @param password mật khẩu gốc
     * @return chuỗi "pbkdf2_sha256$vòng$salt$hash"
     */
    public static String hash(String password) throws GeneralSecurityException {
        // Sinh salt ngẫu nhiên.
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        // Tính giá trị băm PBKDF2.
        byte[] hash = derive(password, salt, ITERATIONS, HASH_BITS);
        // Bộ mã hoá Base64.
        Base64.Encoder encoder = Base64.getEncoder();
        // Ghép thành chuỗi lưu trữ (lưu cả số vòng và salt để kiểm tra lại sau này).
        return "pbkdf2_sha256$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /**
     * Kiểm tra mật khẩu có khớp chuỗi băm đã lưu không.
     *
     * @param password    mật khẩu người dùng nhập
     * @param encodedHash chuỗi băm lưu trong CSDL
     * @return true nếu khớp
     */
    public static boolean verify(String password, String encodedHash) throws GeneralSecurityException {
        // Không có chuỗi băm: không khớp.
        if (encodedHash == null) {
            return false;
        }
        // Tách 4 phần theo dấu '$' (-1: giữ cả phần rỗng ở cuối).
        String[] parts = encodedHash.split("\\$", -1);
        // Sai định dạng hoặc sai tên thuật toán.
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            // Đọc số vòng, salt và giá trị băm mong đợi.
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException error) {
            // Không phải số / không phải Base64.
            return false;
        }
        // Chặn giá trị bất thường (số vòng quá nhỏ/quá lớn, salt hoặc hash sai độ dài).
        if (iterations < 10_000 || iterations > 1_000_000 || salt.length != SALT_BYTES
                || expected.length != HASH_BITS / Byte.SIZE) {
            return false;
        }
        // Băm lại mật khẩu nhập vào với đúng salt và số vòng đã lưu.
        byte[] actual = derive(password, salt, iterations, expected.length * Byte.SIZE);
        // So sánh thời gian hằng: không lộ số byte trùng qua thời gian phản hồi.
        return MessageDigest.isEqual(expected, actual);
    }

    /** Tốn thời gian tương đương một lần verify thật. */
    public static void burnTime(String password) {
        try {
            // Kiểm tra với hash giả, bỏ qua kết quả.
            verify(password, DUMMY_HASH);
        } catch (GeneralSecurityException ignored) {
            // Không quan tâm lỗi: chỉ cần tốn thời gian.
        }
    }

    // Tính PBKDF2-HMAC-SHA256.
    private static byte[] derive(String password, byte[] salt, int iterations, int bits)
            throws GeneralSecurityException {
        // PBEKeySpec cần mảng ký tự.
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, bits);
        try {
            // Chạy thuật toán và lấy mảng byte kết quả.
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            // Luôn xoá mật khẩu khỏi bộ nhớ sau khi dùng (dù thành công hay lỗi).
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
