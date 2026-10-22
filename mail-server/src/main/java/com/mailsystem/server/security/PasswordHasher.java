package com.mailsystem.server.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Băm mật khẩu bằng PBKDF2-HMAC-SHA256 với salt ngẫu nhiên.
 * Định dạng lưu: pbkdf2_sha256$&lt;vòng lặp&gt;$&lt;salt base64&gt;$&lt;hash base64&gt;.
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hash giả để vẫn tốn thời gian băm khi email không tồn tại (chống dò email theo thời gian). */
    private static final String DUMMY_HASH;

    static {
        try {
            DUMMY_HASH = hash("dummy-password-for-timing");
        } catch (GeneralSecurityException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private PasswordHasher() {
    }

    public static String hash(String password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS, HASH_BITS);
        Base64.Encoder encoder = Base64.getEncoder();
        return "pbkdf2_sha256$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    public static boolean verify(String password, String encodedHash) throws GeneralSecurityException {
        if (encodedHash == null) {
            return false;
        }
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException error) {
            return false;
        }
        if (iterations < 10_000 || iterations > 1_000_000 || salt.length != SALT_BYTES
                || expected.length != HASH_BITS / Byte.SIZE) {
            return false;
        }
        byte[] actual = derive(password, salt, iterations, expected.length * Byte.SIZE);
        // So sánh thời gian hằng: không lộ số byte trùng qua thời gian phản hồi.
        return MessageDigest.isEqual(expected, actual);
    }

    /** Tốn thời gian tương đương một lần verify thật. */
    public static void burnTime(String password) {
        try {
            verify(password, DUMMY_HASH);
        } catch (GeneralSecurityException ignored) {
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int bits)
            throws GeneralSecurityException {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
