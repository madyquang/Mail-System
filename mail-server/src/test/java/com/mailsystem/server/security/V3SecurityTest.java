package com.mailsystem.server.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TOTP (RFC 6238) và bộ đếm đăng nhập sai lưu bền. */
class V3SecurityTest {

    /** Khoá "12345678901234567890" của phụ lục B, RFC 6238. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void totpMatchesRfc6238TestVector() {
        // T = 59 giây -> bước 1, mã 8 chữ số 94287082 -> 6 chữ số cuối 287082.
        assertEquals("287082", Totp.code(RFC_SECRET, 1));
        assertEquals(1, Totp.verify(RFC_SECRET, "287082", 59));
    }

    @Test
    void totpAcceptsOneStepClockDriftButNotMore() {
        String secret = Totp.newSecret();
        long now = 1_000_000;
        long step = now / Totp.STEP_SECONDS;
        assertEquals(step - 1, Totp.verify(secret, Totp.code(secret, step - 1), now));
        assertEquals(step + 1, Totp.verify(secret, Totp.code(secret, step + 1), now));
        assertEquals(-1, Totp.verify(secret, Totp.code(secret, step + 3), now));
        assertEquals(-1, Totp.verify(secret, "12ab56", now));
        assertTrue(Totp.otpauthUri("a@b.c", secret).startsWith("otpauth://totp/MailSystem%3Aa%40b.c?secret="));
    }

    @Test
    void lockoutSurvivesServerRestartThroughTheStore() {
        ThrottleStore store = ThrottleStore.inMemory();
        LoginThrottle before = new LoginThrottle(3, Duration.ofMinutes(5), store);
        before.recordFailure("u@x", "1.1.1.1");
        before.recordFailure("u@x", "1.1.1.1");
        assertTrue(before.recordFailure("u@x", "1.1.1.1"));

        // "Khởi động lại": đối tượng mới, cùng kho lưu.
        LoginThrottle after = new LoginThrottle(3, Duration.ofMinutes(5), store);
        assertFalse(after.lockRemaining("u@x", "2.2.2.2").isZero());

        after.recordSuccess("u@x", "1.1.1.1");
        assertTrue(new LoginThrottle(3, Duration.ofMinutes(5), store).lockRemaining("u@x", "2.2.2.2").isZero());
    }
}
