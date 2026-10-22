package com.mailsystem.server.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ResumeTokensTest {

    @Test
    void tokenIsSingleUse() {
        ResumeTokens tokens = new ResumeTokens(Clock.systemUTC());
        ResumeTokens.Issued issued = tokens.issue(42, Duration.ofMinutes(10));
        assertNotEquals(issued.token(), issued.hash(), "chỉ lưu hash, không lưu token");
        assertEquals(42, tokens.redeem(issued.token()));
        assertEquals(-1, tokens.redeem(issued.token()), "dùng lại token phải bị từ chối");
    }

    @Test
    void expiredAndRevokedTokensAreRejected() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ResumeTokens past = new ResumeTokens(Clock.fixed(start, ZoneOffset.UTC));
        ResumeTokens.Issued old = past.issue(1, Duration.ofSeconds(-1));
        assertEquals(-1, past.redeem(old.token()));

        ResumeTokens tokens = new ResumeTokens(Clock.systemUTC());
        ResumeTokens.Issued a = tokens.issue(7, Duration.ofMinutes(10));
        ResumeTokens.Issued b = tokens.issue(7, Duration.ofMinutes(10));
        tokens.revokeAll(7);
        assertEquals(-1, tokens.redeem(a.token()));
        assertEquals(-1, tokens.redeem(b.token()));
        assertEquals(-1, tokens.redeem("short"));
    }
}
