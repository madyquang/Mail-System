// Gói "app": trạng thái và điều hướng chung của ứng dụng Client.
package com.mailsystem.client.app;

// Lỗi từ Server, gọi Server, lỗi mất kết nối, kết nối, tiện ích; JavaFX (luồng giao diện, thuộc tính chỉ đọc).
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

// Lỗi vào/ra, giấy hẹn kết quả, lỗi chờ, Consumer.
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

    // Các mức chờ giữa các lần thử (giây); bỏ cuộc sau 5 phút; đối tượng duy nhất.
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
    private static final long GIVE_UP_AFTER_MILLIS = 5 * 60_000L;
    private static final Reconnector INSTANCE = new Reconnector();

    // Dòng trạng thái hiển thị; số lần kết nối lại thành công.
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper();
    private final ReadOnlyLongWrapper reconnects = new ReadOnlyLongWrapper();
    // Giấy hẹn "đã kết nối lại" (ban đầu coi như đã xong); thread đang thử; việc làm khi bỏ cuộc.
    private volatile CompletableFuture<Void> reconnected = CompletableFuture.completedFuture(null);
    private volatile Thread worker;
    private volatile Consumer<String> onGiveUp = reason -> { };

    // Constructor private (singleton).
    private Reconnector() {
    }

    /** @return đối tượng Reconnector duy nhất */
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

    /** Việc làm khi bỏ cuộc (vd về màn đăng nhập). */
    public void setOnGiveUp(Consumer<String> handler) {
        this.onGiveUp = handler;
    }

    /** Đang trong quá trình kết nối lại? */
    public boolean isActive() {
        return worker != null;
    }

    /** Hoàn tất khi đã kết nối + đăng nhập lại; thất bại nếu bỏ cuộc. */
    public CompletableFuture<Void> awaitReconnected() {
        return reconnected;
    }

    /** @return false nếu không thể tự kết nối lại (chưa đăng nhập / không có token) */
    public synchronized boolean start(String reason) {
        // Đang thử rồi: không tạo thread thứ hai.
        if (worker != null) {
            return true;
        }
        // Chưa đăng nhập hoặc không có token: không tự kết nối lại được.
        if (Session.account() == null || Session.resumeToken() == null) {
            return false;
        }
        // Giấy hẹn mới cho lần kết nối lại này.
        reconnected = new CompletableFuture<>();
        // Máy chủ cần kết nối lại.
        String host = Session.serverHost();
        int port = Session.serverPort();
        // Thread nền (daemon) chạy vòng thử lại.
        Thread thread = new Thread(() -> run(host, port, reason), "mail-reconnect");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        return true;
    }

    /** Người dùng đăng xuất / thoát trong lúc đang thử lại. */
    public synchronized void cancel() {
        // Dừng thread đang thử (nếu có) và báo giấy hẹn thất bại.
        Thread current = worker;
        worker = null;
        if (current != null) {
            current.interrupt();
            reconnected.completeExceptionally(new ConnectionLostException("Đã huỷ kết nối lại."));
        }
        // Ẩn dải thông báo.
        Platform.runLater(() -> status.set(null));
    }

    // Vòng thử lại (chạy trên thread nền).
    private void run(String host, int port, String reason) {
        // Thời điểm bắt đầu (để tính 5 phút bỏ cuộc).
        long started = System.currentTimeMillis();
        ServerConnection connection = ServerConnection.getInstance();
        // Lặp tới khi thành công, bỏ cuộc, hoặc bị huỷ (worker không còn là thread này).
        for (int attempt = 1; worker == Thread.currentThread(); attempt++) {
            // Mức chờ của lần thử này (từ lần thứ 6 trở đi luôn 30 giây).
            long delay = BACKOFF_SECONDS[Math.min(attempt - 1, BACKOFF_SECONDS.length - 1)];
            // Đếm ngược từng giây, cập nhật dòng trạng thái.
            for (long left = delay; left > 0 && worker == Thread.currentThread(); left--) {
                showStatus(reason + " Thử kết nối lại sau " + left + " giây (lần " + attempt + ")...");
                if (!sleep(1000)) {
                    return;
                }
            }
            // Hết giờ chờ: thử kết nối.
            showStatus("Đang kết nối lại tới " + host + ":" + port + "...");
            try {
                // Mở lại TCP + TLS.
                connection.connect(host, port);
                // Gửi RESUME_SESSION với token, chờ tối đa 15 giây.
                MailApi.LoginResult result = MailApi.get().resume(Session.resumeToken()).get(15, TimeUnit.SECONDS);
                // Lưu token mới; báo thành công.
                Session.setResumeToken(result.resumeToken());
                finish(null);
                return;
            // Lỗi khi chờ kết quả.
            } catch (ExecutionException error) {
                if (error.getCause() instanceof ApiException api) {
                    // Server từ chối token (hết hạn, mật khẩu đã đổi, đã đăng xuất mọi nơi): phải đăng nhập lại.
                    connection.disconnect();
                    finish(api.getMessage());
                    return;
                }
                // Lỗi khác: ghi lý do để hiện ở lần chờ sau.
                reason = Fx.message(error);
            // Chưa kết nối được (mạng / máy chủ chưa lên / hết giờ).
            } catch (IOException | TimeoutException error) {
                reason = "Chưa kết nối được (" + Fx.message(error) + ").";
            // Bị huỷ.
            } catch (InterruptedException error) {
                return;
            }
            // Đã thử quá 5 phút: bỏ cuộc.
            if (System.currentTimeMillis() - started > GIVE_UP_AFTER_MILLIS) {
                finish("Không kết nối lại được tới máy chủ sau 5 phút.");
                return;
            }
        }
    }

    // Kết thúc: failure null = thành công, khác null = bỏ cuộc kèm lý do.
    private synchronized void finish(String failure) {
        // Không phải thread hiện hành (đã bị huỷ): bỏ qua.
        if (worker != Thread.currentThread()) {
            return;
        }
        worker = null;
        // Hoàn thành giấy hẹn (các request đang chờ sẽ được gửi lại hoặc báo lỗi).
        CompletableFuture<Void> future = reconnected;
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(new ConnectionLostException(failure));
        }
        // Cập nhật giao diện: ẩn dải; thành công thì tăng bộ đếm (màn chính tải lại), thất bại thì gọi onGiveUp.
        Platform.runLater(() -> {
            status.set(null);
            if (failure == null) {
                reconnects.set(reconnects.get() + 1);
            } else {
                onGiveUp.accept(failure);
            }
        });
    }

    // Đặt dòng trạng thái trên luồng giao diện (nếu vẫn đang thử).
    private void showStatus(String text) {
        Platform.runLater(() -> {
            if (worker != null) {
                status.set(text);
            }
        });
    }

    // Ngủ millis mili giây; bị ngắt thì trả false.
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException error) {
            return false;
        }
    }
}
