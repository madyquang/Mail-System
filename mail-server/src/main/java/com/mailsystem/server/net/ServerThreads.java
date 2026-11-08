// Gói "net": phần mạng của Server.
package com.mailsystem.server.net;

// ServerConfig: đọc cấu hình server.threads (virtual/platform).
import com.mailsystem.server.config.ServerConfig;

// ExecutorService / Executors: bộ chạy tác vụ trên các thread.
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
// ThreadFactory: cách tạo thread (đặt tên, daemon).
import java.util.concurrent.ThreadFactory;
// AtomicInteger: bộ đếm an toàn đa luồng (đánh số thread).
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chọn loại thread phục vụ kết nối.
 *
 * Platform thread = thread của hệ điều hành (~1 MB stack, tạo/chuyển ngữ cảnh
 * tốn kém): vài trăm kết nối là ổn, vài nghìn thì tốn bộ nhớ.
 * Virtual thread (Java 21+) = thread nhẹ do JVM quản lý: khi một thread chặn
 * ở read()/write() socket, JVM gỡ nó khỏi thread hệ điều hành để chạy việc
 * khác. Code vẫn viết kiểu blocking dễ hiểu như cũ nhưng phục vụ được hàng
 * nghìn kết nối. Dự án yêu cầu Java 25 và gọi API virtual thread trực tiếp;
 * platform thread vẫn có thể được chọn bằng cấu hình.
 */
public final class ServerThreads {

    // Chế độ đang dùng (volatile: thread khác đọc thấy giá trị mới nhất).
    private static volatile String activeMode = "platform";

    // Constructor private: lớp chỉ có hàm tĩnh.
    private ServerThreads() {
    }

    /**
     * Tạo bộ chạy tác vụ cho các kết nối theo cấu hình.
     *
     * @return ExecutorService: mỗi kết nối một virtual thread, hoặc pool platform thread
     */
    public static ExecutorService newConnectionExecutor() {
        // Đọc cấu hình "server.threads".
        String mode = ServerConfig.threadMode();
        // Khác "platform" (mặc định "virtual") thì dùng virtual thread.
        if (!"platform".equalsIgnoreCase(mode)) {
            activeMode = "virtual";
            // Mỗi tác vụ chạy trên một virtual thread mới.
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        // Chế độ platform thread.
        activeMode = "platform";
        // Bộ đếm để đặt tên thread mail-client-1, mail-client-2...
        AtomicInteger number = new AtomicInteger();
        // Cách tạo thread: đặt tên dễ đọc khi gỡ lỗi, daemon để không giữ JVM sống khi tắt.
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "mail-client-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // Pool tạo thread khi cần và tái sử dụng thread rảnh.
        return Executors.newCachedThreadPool(factory);
    }

    /** "virtual" hoặc "platform" (sau khi newConnectionExecutor đã được gọi). */
    public static String activeMode() {
        return activeMode;
    }
}
