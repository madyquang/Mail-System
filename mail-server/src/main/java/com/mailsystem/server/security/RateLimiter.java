package com.mailsystem.server.security;

/**
 * Token bucket cho MỘT kết nối: mỗi request tốn 1 token; token được nạp lại
 * đều đặn ratePerSecond token/giây, tối đa burst token. Cho phép dồn dập ngắn
 * (VD: mở thư mục rồi tải 20 khối tệp) nhưng chặn client gửi request vô hạn
 * để làm quá tải Server/MySQL.
 */
public class RateLimiter {

    private final double ratePerNano;
    private final double capacity;
    private double tokens;
    private long lastRefill;

    public RateLimiter(int ratePerSecond, int burst) {
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.capacity = burst;
        this.tokens = burst;
        this.lastRefill = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(System.nanoTime());
    }

    synchronized boolean tryAcquire(long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - lastRefill) * ratePerNano);
        lastRefill = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
