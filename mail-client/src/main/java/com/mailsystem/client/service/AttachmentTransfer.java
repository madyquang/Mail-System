package com.mailsystem.client.service;

import com.mailsystem.client.api.ApiException;
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Reconnector;
import com.mailsystem.client.network.ConnectionLostException;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.protocol.ProtocolConstants;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

/**
 * Truyền tệp đính kèm theo khối (≤ 512 KB/khối) theo kiểu stop-and-wait: gửi một
 * khối, chờ Server xác nhận số byte đã nhận rồi gửi tiếp. Đọc/ghi đĩa chạy
 * trên executor riêng để không chặn UI thread hay thread nghe mạng.
 *
 * TRUYỀN TIẾP (resume): vì mỗi khối mang offset, rớt mạng giữa chừng không phải
 * gửi lại từ đầu. Tải lên: sau khi Reconnector nối lại, hỏi UPLOAD_STATUS xem
 * Server đã nhận bao nhiêu byte rồi gửi tiếp từ đó. Tải xuống: tệp .mailpart giữ
 * lại phần đã nhận, lần sau (kể cả sau khi mở lại ứng dụng) tải tiếp từ kích thước của nó.
 */
public final class AttachmentTransfer {

    private static final int MAX_RESUMES = 5;

    private static final ExecutorService FILE_IO = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "attachment-io");
        thread.setDaemon(true);
        return thread;
    });

    private AttachmentTransfer() {
    }

    /** Tải lần lượt các tệp lên; progress nhận tổng số byte đã gửi. Kết quả: danh sách uploadId. */
    public static CompletableFuture<List<String>> uploadAll(List<File> files, LongConsumer progress) {
        CompletableFuture<List<String>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        long[] uploadedBefore = {0};
        for (File file : files) {
            chain = chain.thenCompose(ids -> {
                long base = uploadedBefore[0];
                return uploadChunk(file, file.length(), null, 0, sent -> progress.accept(base + sent), 0)
                        .thenApply(uploadId -> {
                            uploadedBefore[0] += file.length();
                            ids.add(uploadId);
                            return ids;
                        });
            });
        }
        return chain;
    }

    private static CompletableFuture<String> uploadChunk(File file, long size, String uploadId, long offset,
            LongConsumer progress, int resumes) {
        int length = (int) Math.min(ProtocolConstants.CHUNK_SIZE_BYTES, size - offset);
        CompletableFuture<MailApi.UploadProgress> sent = CompletableFuture
                .supplyAsync(() -> readChunk(file, offset, length), FILE_IO)
                .thenCompose(chunk -> MailApi.get().uploadChunk(uploadId, file.getName(), size, offset,
                        Base64.getEncoder().encodeToString(chunk)));
        return sent.handle((result, error) -> {
            if (error == null) {
                progress.accept(result.receivedBytes());
                if (result.receivedBytes() >= size) {
                    return CompletableFuture.completedFuture(result.uploadId());
                }
                return uploadChunk(file, size, result.uploadId(), result.receivedBytes(), progress, resumes);
            }
            if (!connectionLost(error) || !Reconnector.get().isActive() || resumes >= MAX_RESUMES) {
                return CompletableFuture.<String>failedFuture(unwrap(error));
            }
            // Rớt mạng: đợi kết nối lại, hỏi Server đã nhận tới đâu rồi gửi tiếp.
            CompletableFuture<Void> back = Reconnector.get().awaitReconnected();
            if (uploadId == null) {
                return back.thenCompose(ignored -> uploadChunk(file, size, null, 0, progress, resumes + 1));
            }
            return back.thenCompose(ignored -> MailApi.get().uploadStatus(uploadId))
                    .thenCompose(status -> uploadChunk(file, size, status.uploadId(), status.receivedBytes(),
                            progress, resumes + 1));
        }).thenComposeAsync(next -> next, FILE_IO);
    }

    private static boolean connectionLost(Throwable error) {
        return unwrap(error) instanceof ConnectionLostException;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static byte[] readChunk(File file, long offset, int length) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[length];
            input.seek(offset);
            input.readFully(chunk);
            return chunk;
        } catch (EOFException error) {
            throw new UncheckedIOException(new IOException("Tệp " + file.getName() + " đã bị thay đổi khi đang gửi."));
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException("Không đọc được tệp " + file.getName() + "."));
        }
    }

    /**
     * Tải tệp xuống theo khối vào tệp tạm .mailpart, kiểm tra đủ kích thước rồi mới
     * đổi tên thành tệp đích (người dùng không bao giờ thấy tệp tải dở). Nếu đã có
     * .mailpart của lần tải trước bị gián đoạn thì tải tiếp từ cuối tệp đó.
     */
    public static CompletableFuture<Path> download(Attachment metadata, Path destination, LongConsumer progress) {
        Path temporary = destination.toAbsolutePath().resolveSibling(
                "." + destination.getFileName() + "." + metadata.getAttachmentId() + ".mailpart");
        long resumeFrom;
        try {
            resumeFrom = Files.exists(temporary) ? Files.size(temporary) : 0;
            if (resumeFrom > metadata.getSizeBytes()) {
                Files.delete(temporary);
                resumeFrom = 0;
            }
            if (resumeFrom == 0) {
                Files.write(temporary, new byte[0]);
            }
        } catch (IOException error) {
            return CompletableFuture.failedFuture(new IOException("Không tạo được tệp tạm: " + error.getMessage()));
        }
        if (resumeFrom > 0) {
            progress.accept(resumeFrom);
        }
        CompletableFuture<Void> transfer = resumeFrom >= metadata.getSizeBytes()
                ? CompletableFuture.completedFuture(null)
                : downloadChunk(metadata, temporary, resumeFrom, progress);
        return transfer
                .thenApplyAsync(ignored -> {
                    try {
                        if (Files.size(temporary) != metadata.getSizeBytes()) {
                            throw new IOException("Kích thước tệp tải về không khớp.");
                        }
                        try {
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException error) {
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return destination;
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }, FILE_IO)
                .whenComplete((path, error) -> {
                    // Mất kết nối: GIỮ tệp .mailpart để lần sau tải tiếp; lỗi dữ liệu: xoá.
                    if (error != null && !connectionLost(error)) {
                        try {
                            Files.deleteIfExists(temporary);
                        } catch (IOException ignored) {
                        }
                    }
                });
    }

    private static CompletableFuture<Void> downloadChunk(Attachment metadata, Path temporary, long offset,
            LongConsumer progress) {
        return MailApi.get().downloadChunk(metadata.getAttachmentId(), offset)
                .thenComposeAsync(chunk -> {
                    if (chunk.getAttachmentId() != metadata.getAttachmentId()
                            || chunk.getSizeBytes() != metadata.getSizeBytes() || chunk.getDataBase64() == null) {
                        throw new CompletionException(new ApiException("Dữ liệu tệp tải về không khớp."));
                    }
                    byte[] data = Base64.getDecoder().decode(chunk.getDataBase64());
                    if (data.length == 0 && offset < metadata.getSizeBytes()) {
                        throw new CompletionException(new ApiException("Dữ liệu tệp tải về không đầy đủ."));
                    }
                    try {
                        // Ghi đúng vị trí offset (không APPEND mù quáng): khối gửi lại không bị nhân đôi.
                        try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
                            output.setLength(offset);
                            output.seek(offset);
                            output.write(data);
                        }
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                    long next = offset + data.length;
                    progress.accept(next);
                    return next < metadata.getSizeBytes()
                            ? downloadChunk(metadata, temporary, next, progress)
                            : CompletableFuture.completedFuture(null);
                }, FILE_IO);
    }
}
