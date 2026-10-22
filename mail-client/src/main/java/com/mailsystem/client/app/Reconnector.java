package com.mailsystem.client.app;

import com.mailsystem.client.api.ApiException;
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.network.ConnectionLostException;
import com.mailsystem.client.network.ServerConnection;
import com.mailsystem.client.ui.Fx;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * TỰ KẾT NỐI LẠI khi rớt mạng (Wi-Fi chập chờn, Server khởi động lại).
 *
 * Thử lại theo EXPONENTIAL BACKOFF (1, 2, 4, 8, 16, 30, 30... giây): thử dồn
 * dập sẽ làm Server vừa khởi động lại bị quá tải bởi hàng trăm Client cùng lúc.
 * Mỗi lần: mở lại TCP + TLS, rồi gửi RESUME_SESSION với token một-lần nhận lúc
 * đăng nhập (không phải lưu mật khẩu). Server trả token mới cho lần sau.
 *
 * Trong lúc chờ, các request ĐỌC (xem MailApi.RETRY_SAFE) được giữ lại và gửi
 * lại khi kết nối xong; giao diện hiện dải thông báo thay vì đá về màn đăng nhập.
 */
public final class Reconnector {

    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
    private static final long GIVE_UP_AFTER_MILLIS = 5 * 60_000L;
    private static final Reconnector INSTANCE = new Reconnector();

    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper();
    private final ReadOnlyLongWrapper reconnects = new ReadOnlyLongWrapper();
    private volatile CompletableFuture<Void> reconnected = CompletableFuture.completedFuture(null);
    private volatile Thread worker;
    private volatile Consumer<String> onGiveUp = reason -> { };

    private Reconnector() {
    }

    public static Reconnector get() {
        return INSTANCE;
    }

    /** Dòng trạng thái để hiển thị (null khi đang kết nối bình thường). Chỉ đọc trên UI thread. */
    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    /** Tăng mỗi lần kết nối lại thành công: màn hình chính nghe để tải lại dữ liệu bị lỡ. */
    public ReadOnlyLongProperty reconnectsProperty() {
        return reconnects.getReadOnlyProperty();
    }

    public void setOnGiveUp(Consumer<String> handler) {
        this.onGiveUp = handler;
    }

    public boolean isActive() {
        return worker != null;
    }

    /** Hoàn tất khi đã kết nối + đăng nhập lại; thất bại nếu bỏ cuộc. */
    public CompletableFuture<Void> awaitReconnected() {
        return reconnected;
    }

    /** @return false nếu không thể tự kết nối lại (chưa đăng nhập / không có token) */
    public synchronized boolean start(String reason) {
        if (worker != null) {
            return true;
        }
        if (Session.account() == null || Session.resumeToken() == null) {
            return false;
        }
        reconnected = new CompletableFuture<>();
        String host = Session.serverHost();
        int port = Session.serverPort();
        Thread thread = new Thread(() -> run(host, port, reason), "mail-reconnect");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        return true;
    }

    /** Người dùng đăng xuất / thoát trong lúc đang thử lại. */
    public synchronized void cancel() {
        Thread current = worker;
        worker = null;
        if (current != null) {
            current.interrupt();
            reconnected.completeExceptionally(new ConnectionLostException("Đã huỷ kết nối lại."));
        }
        Platform.runLater(() -> status.set(null));
    }

    private void run(String host, int port, String reason) {
        long started = System.currentTimeMillis();
        ServerConnection connection = ServerConnection.getInstance();
        for (int attempt = 1; worker == Thread.currentThread(); attempt++) {
            long delay = BACKOFF_SECONDS[Math.min(attempt - 1, BACKOFF_SECONDS.length - 1)];
            for (long left = delay; left > 0 && worker == Thread.currentThread(); left--) {
                showStatus(reason + " Thử kết nối lại sau " + left + " giây (lần " + attempt + ")...");
                if (!sleep(1000)) {
                    return;
                }
            }
            showStatus("Đang kết nối lại tới " + host + ":" + port + "...");
            try {
                connection.connect(host, port);
                MailApi.LoginResult result = MailApi.get().resume(Session.resumeToken()).get(15, TimeUnit.SECONDS);
                Session.setResumeToken(result.resumeToken());
                finish(null);
                return;
            } catch (ExecutionException error) {
                if (error.getCause() instanceof ApiException api) {
                    // Server từ chối token (hết hạn, mật khẩu đã đổi, đã đăng xuất mọi nơi): phải đăng nhập lại.
                    connection.disconnect();
                    finish(api.getMessage());
                    return;
                }
                reason = Fx.message(error);
            } catch (IOException | TimeoutException error) {
                reason = "Chưa kết nối được (" + Fx.message(error) + ").";
            } catch (InterruptedException error) {
                return;
            }
            if (System.currentTimeMillis() - started > GIVE_UP_AFTER_MILLIS) {
                finish("Không kết nối lại được tới máy chủ sau 5 phút.");
                return;
            }
        }
    }

    private synchronized void finish(String failure) {
        if (worker != Thread.currentThread()) {
            return;
        }
        worker = null;
        CompletableFuture<Void> future = reconnected;
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(new ConnectionLostException(failure));
        }
        Platform.runLater(() -> {
            status.set(null);
            if (failure == null) {
                reconnects.set(reconnects.get() + 1);
            } else {
                onGiveUp.accept(failure);
            }
        });
    }

    private void showStatus(String text) {
        Platform.runLater(() -> {
            if (worker != null) {
                status.set(text);
            }
        });
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException error) {
            return false;
        }
    }
}
