package com.mailsystem.client.service;

import com.mailsystem.client.api.ApiException;
import com.mailsystem.client.api.MailApi;
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
import java.nio.file.StandardOpenOption;
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
 */
public final class AttachmentTransfer {

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
                return uploadChunk(file, file.length(), null, 0, sent -> progress.accept(base + sent))
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
            LongConsumer progress) {
        int length = (int) Math.min(ProtocolConstants.CHUNK_SIZE_BYTES, size - offset);
        return CompletableFuture.supplyAsync(() -> readChunk(file, offset, length), FILE_IO)
                .thenCompose(chunk -> MailApi.get().uploadChunk(uploadId, file.getName(), size, offset,
                        Base64.getEncoder().encodeToString(chunk)))
                .thenComposeAsync(result -> {
                    progress.accept(result.receivedBytes());
                    if (result.receivedBytes() >= size) {
                        return CompletableFuture.completedFuture(result.uploadId());
                    }
                    return uploadChunk(file, size, result.uploadId(), result.receivedBytes(), progress);
                }, FILE_IO);
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
     * Tải tệp xuống theo khối vào tệp tạm .part, kiểm tra đủ kích thước rồi mới
     * đổi tên thành tệp đích (người dùng không bao giờ thấy tệp tải dở).
     */
    public static CompletableFuture<Path> download(Attachment metadata, Path destination, LongConsumer progress) {
        Path temporary;
        try {
            temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), ".mail-download-", ".part");
        } catch (IOException error) {
            return CompletableFuture.failedFuture(new IOException("Không tạo được tệp tạm: " + error.getMessage()));
        }
        return downloadChunk(metadata, temporary, 0, progress)
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
                    if (error != null) {
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
                        Files.write(temporary, data, offset == 0
                                ? new StandardOpenOption[] {StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
                                : new StandardOpenOption[] {StandardOpenOption.APPEND});
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
