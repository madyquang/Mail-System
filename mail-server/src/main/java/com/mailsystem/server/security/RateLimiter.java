// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

/**
 * Token bucket cho MỘT kết nối: mỗi request tốn 1 token; token được nạp lại
 * đều đặn ratePerSecond token/giây, tối đa burst token. Cho phép dồn dập ngắn
 * (VD: mở thư mục rồi tải 20 khối tệp) nhưng chặn client gửi request vô hạn
 * để làm quá tải Server/MySQL.
 */
public class RateLimiter {

    // Số token nạp thêm mỗi nano giây (= ratePerSecond / 1 tỷ).
    private final double ratePerNano;
    // Sức chứa tối đa của "xô" (= burst).
    private final double capacity;
    // Số token hiện có (số thực vì nạp dần theo thời gian).
    private double tokens;
    // Thời điểm (nano giây) lần nạp token gần nhất.
    private long lastRefill;

    /**
     * Tạo bộ giới hạn.
     *
     * @param ratePerSecond số request trung bình mỗi giây được phép
     * @param burst         số request tối đa được dồn một lúc
     */
    public RateLimiter(int ratePerSecond, int burst) {
        // Đổi tốc độ "mỗi giây" sang "mỗi nano giây" (1 giây = 1.000.000.000 nano giây).
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        // Sức chứa = burst.
        this.capacity = burst;
        // Ban đầu xô đầy.
        this.tokens = burst;
        // Mốc thời gian bắt đầu.
        this.lastRefill = System.nanoTime();
    }

    /**
     * Thử lấy 1 token cho một request.
     *
     * @return true nếu được phép xử lý request, false nếu đang gửi quá nhanh
     */
    public synchronized boolean tryAcquire() {
        // Dùng đồng hồ nano giây của hệ thống.
        return tryAcquire(System.nanoTime());
    }

    // Phiên bản nhận thời điểm từ ngoài vào (để unit test giả lập thời gian).
    synchronized boolean tryAcquire(long nowNanos) {
        // Nạp token theo thời gian đã trôi qua, không vượt quá sức chứa.
        tokens = Math.min(capacity, tokens + (nowNanos - lastRefill) * ratePerNano);
        // Cập nhật mốc nạp.
        lastRefill = nowNanos;
        // Còn ít nhất 1 token: trừ 1 và cho phép.
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        // Hết token: từ chối.
        return false;
    }
}
