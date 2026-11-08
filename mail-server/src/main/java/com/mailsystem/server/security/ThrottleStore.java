// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// Instant: một thời điểm tuyệt đối (UTC).
import java.time.Instant;
// Map: bảng khoá -> giá trị.
import java.util.Map;
// Optional: kết quả "có thể không có".
import java.util.Optional;
// ConcurrentHashMap: Map an toàn khi nhiều thread dùng cùng lúc.
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nơi lưu bộ đếm đăng nhập sai (để khởi động lại Server không xoá khoá).
 * Bản lưu bền dùng CSDL là JdbcThrottleStore; inMemory() dùng cho test.
 */
public interface ThrottleStore {

    /**
     * Bộ đếm của một khoá ("email:..." hoặc "ip:...").
     *
     * @param failures    số lần sai liên tiếp
     * @param lastFailure lần sai gần nhất
     * @param lockedUntil bị khoá tới lúc nào (null = không khoá)
     */
    record Record(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    /** Đọc bộ đếm của khoá; rỗng nếu chưa có. */
    Optional<Record> load(String key);

    /** Ghi (thêm hoặc ghi đè) bộ đếm của khoá. */
    void save(String key, Record record);

    /** Xoá bộ đếm của khoá (khi đăng nhập đúng). */
    void delete(String key);

    /** Chỉ trong bộ nhớ (test, hoặc khi không cần lưu bền). */
    static ThrottleStore inMemory() {
        // Bảng lưu bộ đếm trong RAM.
        Map<String, Record> map = new ConcurrentHashMap<>();
        // Lớp ẩn danh thực hiện interface bằng Map ở trên.
        return new ThrottleStore() {
            @Override
            public Optional<Record> load(String key) {
                // map.get trả null nếu không có -> bọc thành Optional rỗng.
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public void save(String key, Record record) {
                // Thêm hoặc ghi đè.
                map.put(key, record);
            }

            @Override
            public void delete(String key) {
                // Xoá khoá.
                map.remove(key);
            }
        };
    }
}
