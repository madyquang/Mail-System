// Gói "service": các dịch vụ chạy nền của Client (thông báo, tải tệp).
package com.mailsystem.client.service;

// Lỗi Server, gọi Server, kết nối lại, lỗi mất kết nối, DTO tệp đính kèm, hằng số giao thức.
import com.mailsystem.client.api.ApiException;
import com.mailsystem.client.api.MailApi;
import com.mailsystem.client.app.Reconnector;
import com.mailsystem.client.network.ConnectionLostException;
import com.mailsystem.common.model.Attachment;
import com.mailsystem.common.protocol.ProtocolConstants;

// Vào/ra tệp, Base64, danh sách, CompletableFuture, thread pool, LongConsumer.
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

    // Số lần tải tiếp tối đa sau khi rớt mạng.
    private static final int MAX_RESUMES = 5;

    // Thread pool riêng cho đọc / ghi đĩa; thread daemon (không giữ JVM khi thoát).
    private static final ExecutorService FILE_IO = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "attachment-io");
        thread.setDaemon(true);
        return thread;
    });

    // Constructor private: lớp chỉ có hàm tĩnh.
    private AttachmentTransfer() {
    }

    /** Tải lần lượt các tệp lên; progress nhận tổng số byte đã gửi. Kết quả: danh sách uploadId. */
    public static CompletableFuture<List<String>> uploadAll(List<File> files, LongConsumer progress) {
        // Chuỗi tác vụ, bắt đầu bằng danh sách rỗng đã xong.
        CompletableFuture<List<String>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        // Tổng byte của các tệp đã gửi xong (mảng 1 phần tử để sửa được trong lambda).
        long[] uploadedBefore = {0};
        // Nối tiếp từng tệp: tệp sau chỉ bắt đầu khi tệp trước xong.
        for (File file : files) {
            chain = chain.thenCompose(ids -> {
                // Mốc byte trước tệp này; gửi từ offset 0, chưa có uploadId; tiến độ = mốc + byte của tệp này.
                long base = uploadedBefore[0];
                return uploadChunk(file, file.length(), null, 0, sent -> progress.accept(base + sent), 0)
                        // Xong tệp: cộng dồn mốc, thêm uploadId vào danh sách.
                        .thenApply(uploadId -> {
                            uploadedBefore[0] += file.length();
                            ids.add(uploadId);
                            return ids;
                        });
            });
        }
        return chain;
    }

    // Gửi một khối tại offset rồi đệ quy gửi khối tiếp theo.
    private static CompletableFuture<String> uploadChunk(File file, long size, String uploadId, long offset,
            LongConsumer progress, int resumes) {
        // Độ dài khối: tối đa CHUNK_SIZE_BYTES, khối cuối có thể ngắn hơn.
        int length = (int) Math.min(ProtocolConstants.CHUNK_SIZE_BYTES, size - offset);
        // Đọc khối trên FILE_IO, mã hoá Base64 rồi gửi UPLOAD_ATTACHMENT_CHUNK.
        CompletableFuture<MailApi.UploadProgress> sent = CompletableFuture
                .supplyAsync(() -> readChunk(file, offset, length), FILE_IO)
                .thenCompose(chunk -> MailApi.get().uploadChunk(uploadId, file.getName(), size, offset,
                        Base64.getEncoder().encodeToString(chunk)));
        // Xử lý cả thành công lẫn lỗi.
        return sent.handle((result, error) -> {
            if (error == null) {
                // Thành công: báo tiến độ theo số byte Server đã nhận.
                progress.accept(result.receivedBytes());
                // Server đã nhận đủ: trả uploadId.
                if (result.receivedBytes() >= size) {
                    return CompletableFuture.completedFuture(result.uploadId());
                }
                // Chưa đủ: gửi tiếp từ chỗ Server đã nhận.
                return uploadChunk(file, size, result.uploadId(), result.receivedBytes(), progress, resumes);
            }
            // Không phải mất kết nối, hoặc không đang kết nối lại, hoặc đã thử quá số lần: báo lỗi.
            if (!connectionLost(error) || !Reconnector.get().isActive() || resumes >= MAX_RESUMES) {
                return CompletableFuture.<String>failedFuture(unwrap(error));
            }
            // Rớt mạng: đợi kết nối lại, hỏi Server đã nhận tới đâu rồi gửi tiếp.
            CompletableFuture<Void> back = Reconnector.get().awaitReconnected();
            // Khối đầu chưa có uploadId: gửi lại từ đầu.
            if (uploadId == null) {
                return back.thenCompose(ignored -> uploadChunk(file, size, null, 0, progress, resumes + 1));
            }
            // Hỏi UPLOAD_STATUS rồi gửi tiếp từ số byte Server đã nhận.
            return back.thenCompose(ignored -> MailApi.get().uploadStatus(uploadId))
                    .thenCompose(status -> uploadChunk(file, size, status.uploadId(), status.receivedBytes(),
                            progress, resumes + 1));
        }).thenComposeAsync(next -> next, FILE_IO);
    }

    // Lỗi có phải do mất kết nối không.
    private static boolean connectionLost(Throwable error) {
        return unwrap(error) instanceof ConnectionLostException;
    }

    // Gỡ các lớp CompletionException bọc ngoài để lấy lỗi gốc.
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // Đọc length byte từ vị trí offset của tệp.
    private static byte[] readChunk(File file, long offset, int length) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            // Mảng đích, nhảy tới offset, đọc đủ length byte.
            byte[] chunk = new byte[length];
            input.seek(offset);
            input.readFully(chunk);
            return chunk;
        } catch (EOFException error) {
            // Thiếu dữ liệu: tệp đã bị sửa / cắt ngắn khi đang gửi.
            throw new UncheckedIOException(new IOException("Tệp " + file.getName() + " đã bị thay đổi khi đang gửi."));
        } catch (IOException error) {
            // Lỗi đọc khác.
            throw new UncheckedIOException(new IOException("Không đọc được tệp " + file.getName() + "."));
        }
    }

    /**
     * Tải tệp xuống theo khối vào tệp tạm .mailpart, kiểm tra đủ kích thước rồi mới
     * đổi tên thành tệp đích (người dùng không bao giờ thấy tệp tải dở). Nếu đã có
     * .mailpart của lần tải trước bị gián đoạn thì tải tiếp từ cuối tệp đó.
     */
    public static CompletableFuture<Path> download(Attachment metadata, Path destination, LongConsumer progress) {
        // Tệp tạm ẩn cạnh tệp đích: .<tên>.<attachmentId>.mailpart.
        Path temporary = destination.toAbsolutePath().resolveSibling(
                "." + destination.getFileName() + "." + metadata.getAttachmentId() + ".mailpart");
        // Vị trí bắt đầu tải.
        long resumeFrom;
        try {
            // Có tệp tạm: tải tiếp từ kích thước của nó.
            resumeFrom = Files.exists(temporary) ? Files.size(temporary) : 0;
            // Tệp tạm lớn hơn tệp thật (không hợp lệ): xoá, tải lại từ đầu.
            if (resumeFrom > metadata.getSizeBytes()) {
                Files.delete(temporary);
                resumeFrom = 0;
            }
            // Tải từ đầu: tạo tệp tạm rỗng.
            if (resumeFrom == 0) {
                Files.write(temporary, new byte[0]);
            }
        } catch (IOException error) {
            // Không tạo được tệp tạm: trả lỗi.
            return CompletableFuture.failedFuture(new IOException("Không tạo được tệp tạm: " + error.getMessage()));
        }
        // Báo ngay phần đã có sẵn.
        if (resumeFrom > 0) {
            progress.accept(resumeFrom);
        }
        // Đã đủ thì không tải nữa; chưa đủ thì tải từng khối từ resumeFrom.
        CompletableFuture<Void> transfer = resumeFrom >= metadata.getSizeBytes()
                ? CompletableFuture.completedFuture(null)
                : downloadChunk(metadata, temporary, resumeFrom, progress);
        // Tải xong: kiểm tra và đổi tên trên FILE_IO.
        return transfer
                .thenApplyAsync(ignored -> {
                    try {
                        // Kích thước phải khớp metadata.
                        if (Files.size(temporary) != metadata.getSizeBytes()) {
                            throw new IOException("Kích thước tệp tải về không khớp.");
                        }
                        try {
                            // Đổi tên nguyên tử (ghi đè tệp đích nếu có).
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException error) {
                            // Ổ đĩa không hỗ trợ đổi tên nguyên tử: đổi tên thường.
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return destination;
                    } catch (IOException error) {
                        // Bọc lỗi để đi qua CompletableFuture.
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
        // Gửi DOWNLOAD_ATTACHMENT tại offset; xử lý khối nhận được trên FILE_IO.
        return MailApi.get().downloadChunk(metadata.getAttachmentId(), offset)
                .thenComposeAsync(chunk -> {
                    // Khối phải đúng tệp, đúng kích thước và có dữ liệu.
                    if (chunk.getAttachmentId() != metadata.getAttachmentId()
                            || chunk.getSizeBytes() != metadata.getSizeBytes() || chunk.getDataBase64() == null) {
                        throw new CompletionException(new ApiException("Dữ liệu tệp tải về không khớp."));
                    }
                    // Giải mã Base64.
                    byte[] data = Base64.getDecoder().decode(chunk.getDataBase64());
                    // Khối rỗng khi chưa đủ: dữ liệu thiếu.
                    if (data.length == 0 && offset < metadata.getSizeBytes()) {
                        throw new CompletionException(new ApiException("Dữ liệu tệp tải về không đầy đủ."));
                    }
                    try {
                        // Ghi đúng vị trí offset (không APPEND mù quáng): khối gửi lại không bị nhân đôi.
                        try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
                            // Cắt tệp về đúng offset, nhảy tới offset, ghi khối.
                            output.setLength(offset);
                            output.seek(offset);
                            output.write(data);
                        }
                    } catch (IOException error) {
                        // Bọc lỗi ghi đĩa.
                        throw new UncheckedIOException(error);
                    }
                    // Vị trí tiếp theo; báo tiến độ.
                    long next = offset + data.length;
                    progress.accept(next);
                    // Còn thiếu thì tải khối tiếp (đệ quy), đủ rồi thì xong.
                    return next < metadata.getSizeBytes()
                            ? downloadChunk(metadata, temporary, next, progress)
                            : CompletableFuture.completedFuture(null);
                }, FILE_IO);
    }
}
