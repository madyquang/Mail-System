// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// Lỗi từ Server; Platform.runLater để chạy trên luồng giao diện.
import com.mailsystem.client.api.ApiException;
import javafx.application.Platform;

// Các loại lỗi có thể bọc nhau trong chuỗi CompletableFuture; Consumer = hàm nhận một giá trị.
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Tiện ích luồng JavaFX: mọi callback của request mạng chạy trên thread nền,
 * còn control JavaFX chỉ được sửa trên FX Application Thread.
 */
public final class Fx {

    // Constructor private: lớp chỉ có hàm tĩnh.
    private Fx() {
    }

    /** Chạy onSuccess/onError trên UI thread khi future hoàn tất. */
    public static <T> void then(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<String> onError) {
        // Khi future xong (thành công hay lỗi), chuyển về luồng giao diện.
        future.whenComplete((value, error) -> Platform.runLater(() -> {
            // Không lỗi: gọi onSuccess với kết quả.
            if (error == null) {
                onSuccess.accept(value);
            } else {
                // Có lỗi: đổi thành câu dễ hiểu rồi gọi onError.
                onError.accept(message(error));
            }
        }));
    }

    /** Mã lỗi Server (ErrorCodes) nếu lỗi là ApiException có mã, ngược lại null. */
    public static String code(Throwable error) {
        // Bóc các lớp vỏ CompletionException / ExecutionException để lấy lỗi gốc.
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        // Pattern matching: là ApiException thì lấy mã lỗi.
        return cause instanceof ApiException api ? api.code() : null;
    }

    /** Thông báo dễ hiểu từ lỗi của một chuỗi CompletableFuture. */
    public static String message(Throwable error) {
        // Bóc các lớp vỏ để lấy lỗi gốc.
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException
                || cause instanceof UncheckedIOException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        // Hết thời gian chờ.
        if (cause instanceof TimeoutException) {
            return "Máy chủ không phản hồi kịp, vui lòng thử lại.";
        }
        // Lỗi từ Server: câu báo đã là tiếng Việt.
        if (cause instanceof ApiException) {
            return cause.getMessage();
        }
        // Lỗi khác: dùng câu báo của lỗi, trống thì câu mặc định.
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "Không nhận được phản hồi từ máy chủ."
                : cause.getMessage();
    }
}
