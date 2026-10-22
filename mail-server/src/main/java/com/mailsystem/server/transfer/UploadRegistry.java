package com.mailsystem.server.transfer;

import com.mailsystem.server.config.ServerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tệp đang tải lên thuộc về TÀI KHOẢN (không thuộc về một kết nối), nên khi
 * mạng rớt giữa chừng, Client kết nối lại (RESUME_SESSION) rồi hỏi UPLOAD_STATUS
 * và gửi tiếp từ đúng offset Server đã nhận. Tệp bỏ dở quá upload.expiryMinutes
 * bị dọn bởi sweepExpired() (gọi định kỳ).
 */
public final class UploadRegistry {

    private static final Map<Integer, UploadManager> MANAGERS = new ConcurrentHashMap<>();

    private UploadRegistry() {
    }

    public static UploadManager forAccount(int accountId) {
        return MANAGERS.computeIfAbsent(accountId, id -> new UploadManager(ServerConfig.uploadTempDir()));
    }

    /** Đăng xuất chủ động: bỏ hết tệp dở dang của tài khoản. */
    public static void discard(int accountId) {
        UploadManager manager = MANAGERS.remove(accountId);
        if (manager != null) {
            manager.discardAll();
        }
    }

    public static void sweepExpired() {
        long maxIdle = ServerConfig.uploadExpiryMinutes() * 60_000L;
        MANAGERS.entrySet().removeIf(entry -> !entry.getValue().discardIdle(maxIdle));
    }

    public static int pendingUploads() {
        return MANAGERS.values().stream().mapToInt(UploadManager::pendingCount).sum();
    }
}
