package com.mailsystem.client.ui;

import com.mailsystem.client.api.ApiException;
import javafx.application.Platform;

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

    private Fx() {
    }

    /** Chạy onSuccess/onError trên UI thread khi future hoàn tất. */
    public static <T> void then(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<String> onError) {
        future.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error == null) {
                onSuccess.accept(value);
            } else {
                onError.accept(message(error));
            }
        }));
    }

    /** Thông báo dễ hiểu từ lỗi của một chuỗi CompletableFuture. */
    public static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException
                || cause instanceof UncheckedIOException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "Máy chủ không phản hồi kịp, vui lòng thử lại.";
        }
        if (cause instanceof ApiException) {
            return cause.getMessage();
        }
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "Không nhận được phản hồi từ máy chủ."
                : cause.getMessage();
    }
}
