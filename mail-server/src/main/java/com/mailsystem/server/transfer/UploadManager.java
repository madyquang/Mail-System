package com.mailsystem.server.transfer;

import com.mailsystem.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Nhận tệp đính kèm theo từng khối (chunked upload) cho MỘT kết nối.
 *
 * Gửi cả tệp 25MB trong một frame buộc hai phía giữ toàn bộ chuỗi Base64
 * (~33MB) trong RAM và chặn kết nối cho tới khi gửi xong. Chia thành các khối
 * ≤ CHUNK_SIZE_BYTES giúp mỗi frame nhỏ, bộ nhớ ổn định, có thể hiện tiến độ,
 * và EVENT real-time vẫn chen vào được giữa các khối.
 *
 * Mỗi khối mang offset; Server chỉ nhận khối có offset đúng bằng số byte đã
 * nhận (TCP đã đảm bảo thứ tự trong một kết nối, kiểm tra này chặn khối gửi
 * lặp/sai ở tầng ứng dụng). Tệp dở dang bị xoá khi kết nối đóng.
 */
public class UploadManager {

    private final Path tempDirectory;
    private final Map<String, PendingUpload> uploads = new LinkedHashMap<>();

    public UploadManager(Path tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    /**
     * Ghi 1 khối. uploadId rỗng nghĩa là bắt đầu tệp mới (offset phải bằng 0).
     */
    public synchronized ChunkResult acceptChunk(String uploadId, String filename, long declaredSize,
            long offset, byte[] data) throws UploadException, IOException {
        if (data == null || data.length == 0 || data.length > ProtocolConstants.CHUNK_SIZE_BYTES) {
            throw new UploadException("Kích thước khối dữ liệu không hợp lệ.");
        }
        PendingUpload upload;
        if (uploadId == null || uploadId.isBlank()) {
            if (offset != 0) {
                throw new UploadException("Khối đầu tiên phải có offset = 0.");
            }
            upload = start(filename, declaredSize);
        } else {
            upload = uploads.get(uploadId);
            if (upload == null) {
                throw new UploadException("Phiên tải lên không tồn tại hoặc đã hết hạn.");
            }
        }

        if (offset != upload.receivedBytes) {
            throw new UploadException("Sai thứ tự khối: máy chủ đang chờ offset " + upload.receivedBytes + ".");
        }
        if (upload.receivedBytes + data.length > upload.declaredSize) {
            discard(upload.id);
            throw new UploadException("Dữ liệu vượt quá kích thước tệp đã khai báo.");
        }
        Files.write(upload.file, data, StandardOpenOption.APPEND);
        upload.receivedBytes += data.length;
        return new ChunkResult(upload.id, upload.receivedBytes, upload.isComplete());
    }

    /**
     * Lấy tệp đã tải lên đầy đủ ra khỏi bộ quản lý. Từ lúc này caller sở hữu
     * tệp tạm (phải di chuyển hoặc xoá nó).
     */
    public synchronized PendingUpload take(String uploadId) throws UploadException {
        PendingUpload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null) {
            throw new UploadException("Tệp đính kèm chưa được tải lên hoặc đã hết hạn.");
        }
        if (!upload.isComplete()) {
            throw new UploadException("Tệp " + upload.filename + " chưa được tải lên đầy đủ.");
        }
        uploads.remove(uploadId);
        return upload;
    }

    /** Xoá mọi tệp dở dang (gọi khi đăng xuất, đổi tài khoản hoặc mất kết nối). */
    public synchronized void discardAll() {
        for (String id : new ArrayList<>(uploads.keySet())) {
            discard(id);
        }
    }

    public synchronized int pendingCount() {
        return uploads.size();
    }

    private PendingUpload start(String rawFilename, long declaredSize) throws UploadException, IOException {
        String filename = rawFilename == null ? "" : rawFilename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).trim();
        if (filename.isBlank() || filename.length() > 255) {
            throw new UploadException("Tên tệp đính kèm không hợp lệ.");
        }
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ProtocolConstants.ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new UploadException("Không hỗ trợ định dạng tệp: " + filename);
        }
        if (declaredSize <= 0 || declaredSize > ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES) {
            throw new UploadException("Kích thước tệp phải từ 1 byte đến 25 MB: " + filename);
        }
        // Giới hạn số tệp dở dang trên một kết nối: tệp cũ nhất bị huỷ để
        // client bỏ dở việc gửi không giữ đĩa của Server mãi mãi.
        while (uploads.size() >= ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
            discard(uploads.keySet().iterator().next());
        }

        Files.createDirectories(tempDirectory);
        Path file = Files.createTempFile(tempDirectory, "upload-", ".part");
        PendingUpload upload = new PendingUpload(UUID.randomUUID().toString(), filename, extension,
                declaredSize, file);
        uploads.put(upload.id, upload);
        return upload;
    }

    private void discard(String uploadId) {
        if (uploads.containsKey(uploadId)) {
            deleteQuietly(uploadId);
            uploads.remove(uploadId);
        }
    }

    private void deleteQuietly(String uploadId) {
        PendingUpload upload = uploads.get(uploadId);
        if (upload == null) {
            return;
        }
        try {
            Files.deleteIfExists(upload.file);
        } catch (IOException error) {
            System.err.println("[UploadManager] Khong xoa duoc tep tam " + upload.file + ": " + error.getMessage());
        }
    }

    /** Xoá tệp tạm sót lại từ lần chạy trước (VD: Server bị tắt đột ngột). */
    public static void cleanTempDirectory(Path tempDirectory) {
        if (!Files.isDirectory(tempDirectory)) {
            return;
        }
        List<Path> leftovers = new ArrayList<>();
        try (var files = Files.list(tempDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(leftovers::add);
        } catch (IOException error) {
            System.err.println("[UploadManager] Khong doc duoc thu muc tam: " + error.getMessage());
            return;
        }
        for (Path leftover : leftovers) {
            try {
                Files.deleteIfExists(leftover);
            } catch (IOException ignored) {
            }
        }
    }

    public record ChunkResult(String uploadId, long receivedBytes, boolean complete) {
    }

    public static final class PendingUpload {
        private final String id;
        private final String filename;
        private final String extension;
        private final long declaredSize;
        private final Path file;
        private long receivedBytes;

        PendingUpload(String id, String filename, String extension, long declaredSize, Path file) {
            this.id = id;
            this.filename = filename;
            this.extension = extension;
            this.declaredSize = declaredSize;
            this.file = file;
        }

        public String id() {
            return id;
        }

        public String filename() {
            return filename;
        }

        public String extension() {
            return extension;
        }

        public long declaredSize() {
            return declaredSize;
        }

        public Path file() {
            return file;
        }

        boolean isComplete() {
            return receivedBytes == declaredSize;
        }
    }

    public static class UploadException extends Exception {
        public UploadException(String message) {
            super(message);
        }
    }
}
