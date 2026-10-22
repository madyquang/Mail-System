package com.mailsystem.server.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Giới hạn số sự kiện theo khoá trong một cửa sổ trượt (VD: số lần đăng ký mỗi IP mỗi giờ). */
public class SlidingWindowLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    SlidingWindowLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** Ghi nhận một sự kiện nếu chưa vượt giới hạn. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        events.compute(key, (k, queue) -> {
            Deque<Instant> value = queue == null ? new ArrayDeque<>() : queue;
            while (!value.isEmpty() && !value.peekFirst().isAfter(now.minus(window))) {
                value.pollFirst();
            }
            if (value.size() < limit) {
                value.addLast(now);
                allowed[0] = true;
            }
            return value.isEmpty() ? null : value;
        });
        return allowed[0];
    }
}
