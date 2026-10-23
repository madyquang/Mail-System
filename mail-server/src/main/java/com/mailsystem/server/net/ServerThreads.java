package com.mailsystem.server.net;

import com.mailsystem.server.config.ServerConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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

    private static volatile String activeMode = "platform";

    private ServerThreads() {
    }

    public static ExecutorService newConnectionExecutor() {
        String mode = ServerConfig.threadMode();
        if (!"platform".equalsIgnoreCase(mode)) {
            activeMode = "virtual";
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        activeMode = "platform";
        AtomicInteger number = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "mail-client-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    /** "virtual" hoặc "platform" (sau khi newConnectionExecutor đã được gọi). */
    public static String activeMode() {
        return activeMode;
    }
}
