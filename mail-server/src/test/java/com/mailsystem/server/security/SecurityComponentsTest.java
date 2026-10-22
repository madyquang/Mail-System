package com.mailsystem.server.security;

import com.mailsystem.common.security.CertificateFingerprints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityComponentsTest {

    /** Đồng hồ điều khiển được để test khoá theo thời gian mà không phải chờ thật. */
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-22T08:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void passwordHashIsSaltedAndVerifiable() throws Exception {
        String first = PasswordHasher.hash("matkhau2026");
        String second = PasswordHasher.hash("matkhau2026");
        assertFalse(first.equals(second), "cùng mật khẩu nhưng salt khác -> hash khác");
        assertTrue(PasswordHasher.verify("matkhau2026", first));
        assertFalse(PasswordHasher.verify("matkhau2027", first));
        assertFalse(PasswordHasher.verify("x", "khong-phai-hash"));
    }

    @Test
    void passwordPolicyRejectsWeakPasswords() {
        assertNotNull(PasswordPolicy.validate("abc123", "an@mail.local"), "quá ngắn");
        assertNotNull(PasswordPolicy.validate("abcdefghij", "an@mail.local"), "thiếu số");
        assertNotNull(PasswordPolicy.validate("1234567890", "an@mail.local"), "thiếu chữ");
        assertNotNull(PasswordPolicy.validate("password123", "an@mail.local"), "quá phổ biến");
        assertNotNull(PasswordPolicy.validate("quanghuy2026", "quanghuy@mail.local"), "chứa tên email");
        assertNotNull(PasswordPolicy.validate("a1".repeat(80), "an@mail.local"), "quá dài");
        assertNull(PasswordPolicy.validate("Thu-tu-2026", "an@mail.local"));
    }

    @Test
    void loginThrottleLocksAfterRepeatedFailuresThenExpires() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), clock);
        assertTrue(throttle.lockRemaining("an@mail.local", "10.0.0.1").isZero());
        assertFalse(throttle.recordFailure("an@mail.local", "10.0.0.1"));
        assertFalse(throttle.recordFailure("an@mail.local", "10.0.0.2"));
        assertTrue(throttle.recordFailure("AN@mail.local", "10.0.0.3"), "lần sai thứ 3 (kể cả khác IP) -> khoá email");
        assertFalse(throttle.lockRemaining("an@mail.local", "10.9.9.9").isZero());
        assertTrue(throttle.lockRemaining("binh@mail.local", "10.9.9.9").isZero(), "tài khoản khác không bị ảnh hưởng");

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertTrue(throttle.lockRemaining("an@mail.local", "10.0.0.1").isZero(), "hết hạn khoá");
    }

    @Test
    void loginThrottleLocksIpTryingManyAccounts() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 12; i++) {
            throttle.recordFailure("user" + i + "@mail.local", "10.0.0.66");
        }
        assertFalse(throttle.lockRemaining("moi@mail.local", "10.0.0.66").isZero(), "IP dò nhiều tài khoản bị khoá");
        throttle.recordSuccess("moi@mail.local", "10.0.0.66");
        assertTrue(throttle.lockRemaining("moi@mail.local", "10.0.0.66").isZero());
    }

    @Test
    void rateLimiterAllowsBurstThenRefills() {
        RateLimiter limiter = new RateLimiter(10, 5);
        long start = System.nanoTime();
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.tryAcquire(start)) {
                allowed++;
            }
        }
        assertEquals(5, allowed, "chỉ cho phép đúng kích thước burst");
        assertTrue(limiter.tryAcquire(start + 200_000_000L), "sau 0,2 s nạp lại 2 token");
    }

    @Test
    void slidingWindowLimiterCountsPerKey() {
        MutableClock clock = new MutableClock();
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, Duration.ofHours(1), clock);
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertFalse(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.2"));
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
    }

    @Test
    void fileSignaturesDetectRenamedFiles() {
        byte[] pdf = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0};
        byte[] zip = {'P', 'K', 3, 4, 20, 0};
        assertTrue(FileSignatures.matches("pdf", pdf));
        assertTrue(FileSignatures.matches("png", png));
        assertTrue(FileSignatures.matches("docx", zip), "docx là tệp ZIP");
        assertFalse(FileSignatures.matches("pdf", exe), "virus.exe đổi tên thành .pdf bị chặn");
        assertFalse(FileSignatures.matches("jpg", png));
        assertTrue(FileSignatures.matches("txt", "Xin chào, đây là văn bản UTF-8.\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FileSignatures.matches("txt", exe), "nhị phân không phải văn bản");
    }

    @Test
    void tlsKeystoreIsGeneratedOnceAndReused(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("tls").resolve("server.p12");
        TlsContextFactory.ServerTls first = TlsContextFactory.loadOrCreate(keystore, "");
        assertTrue(Files.isRegularFile(keystore));
        assertTrue(Files.isRegularFile(keystore.resolveSibling("server.p12.pass")), "mật khẩu ngẫu nhiên được lưu");
        assertEquals("TLSv1.3", first.context().getProtocol());
        TlsContextFactory.ServerTls second = TlsContextFactory.loadOrCreate(keystore, "");
        assertEquals(first.fingerprint(), second.fingerprint(), "khởi động lại dùng lại đúng chứng chỉ");
        assertEquals(first.fingerprint(), CertificateFingerprints.sha256(second.certificate()));
        assertTrue(first.certificate().getSubjectX500Principal().getName().contains("Mail System"));
    }
}
