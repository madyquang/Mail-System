// Gói "transfer": nhận tệp đính kèm tải lên theo từng khối.
package com.mailsystem.server.transfer;

// ServerConfig: đọc cấu hình (thư mục tạm, thời hạn tệp dở dang).
import com.mailsystem.server.config.ServerConfig;

// Map: bảng khoá -> giá trị.
import java.util.Map;
// ConcurrentHashMap: Map an toàn khi nhiều thread dùng cùng lúc.
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tệp đang tải lên thuộc về TÀI KHOẢN (không thuộc về một kết nối), nên khi
 * mạng rớt giữa chừng, Client kết nối lại (RESUME_SESSION) rồi hỏi UPLOAD_STATUS
 * và gửi tiếp từ đúng offset Server đã nhận. Tệp bỏ dở quá upload.expiryMinutes
 * bị dọn bởi sweepExpired() (gọi định kỳ).
 */
public final class UploadRegistry {

    // accountId -> bộ quản lý tệp đang tải lên của tài khoản đó.
    private static final Map<Integer, UploadManager> MANAGERS = new ConcurrentHashMap<>();

    // Constructor private: lớp chỉ có hàm tĩnh.
    private UploadRegistry() {
    }

    /**
     * Lấy (hoặc tạo mới nếu chưa có) bộ quản lý tải lên của một tài khoản.
     *
     * @param accountId tài khoản
     * @return UploadManager của tài khoản
     */
    public static UploadManager forAccount(int accountId) {
        // computeIfAbsent: chưa có thì tạo UploadManager dùng thư mục tạm trong cấu hình (an toàn đa luồng).
        return MANAGERS.computeIfAbsent(accountId, id -> new UploadManager(ServerConfig.uploadTempDir()));
    }

    /** Đăng xuất chủ động: bỏ hết tệp dở dang của tài khoản. */
    public static void discard(int accountId) {
        // Gỡ bộ quản lý khỏi bảng.
        UploadManager manager = MANAGERS.remove(accountId);
        // Có thì xoá mọi tệp tạm của nó.
        if (manager != null) {
            manager.discardAll();
        }
    }

    /** Dọn các tệp tải dở quá thời hạn; bỏ luôn bộ quản lý không còn tệp nào. */
    public static void sweepExpired() {
        // Thời hạn tính bằng mili giây (phút * 60.000).
        long maxIdle = ServerConfig.uploadExpiryMinutes() * 60_000L;
        // discardIdle xoá tệp quá hạn và trả "còn tệp không"; không còn thì gỡ bộ quản lý khỏi bảng.
        MANAGERS.entrySet().removeIf(entry -> !entry.getValue().discardIdle(maxIdle));
    }

    /** @return tổng số tệp đang tải dở của mọi tài khoản (cho trang giám sát) */
    public static int pendingUploads() {
        // Cộng số tệp dở dang của từng bộ quản lý.
        return MANAGERS.values().stream().mapToInt(UploadManager::pendingCount).sum();
    }
}
