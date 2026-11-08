// Gói "transfer": nhận tệp đính kèm tải lên theo từng khối.
package com.mailsystem.server.transfer;

// Giới hạn kích thước khối, số tệp, đuôi tệp cho phép.
import com.mailsystem.common.protocol.ProtocolConstants;

// Vào/ra tệp.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
// Danh sách, Map giữ thứ tự chèn (cũ nhất ở đầu), chữ thường, mã ngẫu nhiên.
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Nhận tệp đính kèm theo từng khối (chunked upload) cho MỘT tài khoản
 * (UploadRegistry giữ mỗi tài khoản một UploadManager).
 *
 * Gửi cả tệp 25MB trong một frame buộc hai phía giữ toàn bộ chuỗi Base64
 * (~33MB) trong RAM và chặn kết nối cho tới khi gửi xong. Chia thành các khối
 * ≤ CHUNK_SIZE_BYTES giúp mỗi frame nhỏ, bộ nhớ ổn định, có thể hiện tiến độ,
 * và EVENT real-time vẫn chen vào được giữa các khối.
 *
 * Mỗi khối mang offset; Server chỉ nhận khối có offset đúng bằng số byte đã
 * nhận (TCP đã đảm bảo thứ tự trong một kết nối, kiểm tra này chặn khối gửi
 * lặp/sai ở tầng ứng dụng). Tệp dở dang được giữ qua các lần kết nối lại để
 * tải tiếp; bị xoá khi đăng xuất hoặc quá thời hạn upload.expiryMinutes.
 */
public class UploadManager {

    // Thư mục chứa tệp tạm.
    private final Path tempDirectory;
    // uploadId -> tệp đang tải (LinkedHashMap: duyệt theo thứ tự thêm, cũ nhất trước).
    private final Map<String, PendingUpload> uploads = new LinkedHashMap<>();

    /** @param tempDirectory thư mục chứa tệp tạm */
    public UploadManager(Path tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    /**
     * Ghi 1 khối. uploadId rỗng nghĩa là bắt đầu tệp mới (offset phải bằng 0).
     * synchronized: hai kết nối của cùng tài khoản không ghi xen vào nhau.
     */
    public synchronized ChunkResult acceptChunk(String uploadId, String filename, long declaredSize,
            long offset, byte[] data) throws UploadException, IOException {
        // Khối rỗng hoặc lớn hơn 512 KB: từ chối.
        if (data == null || data.length == 0 || data.length > ProtocolConstants.CHUNK_SIZE_BYTES) {
            throw new UploadException("Kích thước khối dữ liệu không hợp lệ.");
        }
        PendingUpload upload;
        if (uploadId == null || uploadId.isBlank()) {
            // Tệp mới: khối đầu phải bắt đầu từ byte 0.
            if (offset != 0) {
                throw new UploadException("Khối đầu tiên phải có offset = 0.");
            }
            // Kiểm tra tên/đuôi/kích thước và tạo tệp tạm.
            upload = start(filename, declaredSize);
        } else {
            // Khối tiếp theo của tệp đang tải.
            upload = uploads.get(uploadId);
            if (upload == null) {
                throw new UploadException("Phiên tải lên không tồn tại hoặc đã hết hạn.");
            }
        }

        // Khối phải nối đúng vào cuối phần đã nhận.
        if (offset != upload.receivedBytes) {
            throw new UploadException("Sai thứ tự khối: máy chủ đang chờ offset " + upload.receivedBytes + ".");
        }
        // Gửi nhiều hơn kích thước đã khai báo: huỷ tệp.
        if (upload.receivedBytes + data.length > upload.declaredSize) {
            discard(upload.id);
            throw new UploadException("Dữ liệu vượt quá kích thước tệp đã khai báo.");
        }
        // Ghi nối khối vào cuối tệp tạm.
        Files.write(upload.file, data, StandardOpenOption.APPEND);
        // Cập nhật số byte đã nhận và thời điểm hoạt động.
        upload.receivedBytes += data.length;
        upload.lastActivity = System.currentTimeMillis();
        // Trả tiến độ.
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
        // Chưa nhận đủ byte.
        if (!upload.isComplete()) {
            throw new UploadException("Tệp " + upload.filename + " chưa được tải lên đầy đủ.");
        }
        // Gỡ khỏi danh sách (không bị dọn tự động nữa).
        uploads.remove(uploadId);
        return upload;
    }

    /** Tiến độ của một phiên tải lên (để Client tải tiếp sau khi rớt mạng). */
    public synchronized ChunkResult status(String uploadId) throws UploadException {
        PendingUpload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null) {
            throw new UploadException("Phiên tải lên không tồn tại hoặc đã hết hạn.");
        }
        return new ChunkResult(upload.id, upload.receivedBytes, upload.isComplete());
    }

    /** Xoá các tệp không có khối mới trong maxIdleMillis. @return còn tệp nào không */
    public synchronized boolean discardIdle(long maxIdleMillis) {
        // Mốc thời gian: hoạt động trước mốc này là quá hạn.
        long cutoff = System.currentTimeMillis() - maxIdleMillis;
        // Duyệt trên bản sao các khoá vì discard() xoá khỏi Map trong lúc duyệt.
        for (String id : new ArrayList<>(uploads.keySet())) {
            if (uploads.get(id).lastActivity < cutoff) {
                discard(id);
            }
        }
        return !uploads.isEmpty();
    }

    /** Xoá mọi tệp dở dang (gọi khi đăng xuất hoặc đổi tài khoản). */
    public synchronized void discardAll() {
        for (String id : new ArrayList<>(uploads.keySet())) {
            discard(id);
        }
    }

    /** @return số tệp đang tải dở */
    public synchronized int pendingCount() {
        return uploads.size();
    }

    // Bắt đầu một tệp mới: kiểm tra tên, đuôi, kích thước; tạo tệp tạm.
    private PendingUpload start(String rawFilename, long declaredSize) throws UploadException, IOException {
        // Chuẩn hoá dấu '\' thành '/' rồi chỉ giữ phần tên sau dấu '/' cuối (bỏ mọi đường dẫn).
        String filename = rawFilename == null ? "" : rawFilename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).trim();
        // Tên rỗng hoặc dài quá 255 ký tự.
        if (filename.isBlank() || filename.length() > 255) {
            throw new UploadException("Tên tệp đính kèm không hợp lệ.");
        }
        // Đuôi tệp (phần sau dấu '.' cuối), chữ thường.
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        // Đuôi không nằm trong danh sách cho phép.
        if (!ProtocolConstants.ALLOWED_ATTACHMENT_EXTENSIONS.contains(extension)) {
            throw new UploadException("Không hỗ trợ định dạng tệp: " + filename);
        }
        // Kích thước khai báo phải từ 1 byte tới 25 MB.
        if (declaredSize <= 0 || declaredSize > ProtocolConstants.MAX_ATTACHMENT_TOTAL_BYTES) {
            throw new UploadException("Kích thước tệp phải từ 1 byte đến 25 MB: " + filename);
        }
        // Giới hạn số tệp dở dang của một tài khoản: tệp cũ nhất bị huỷ để
        // client bỏ dở việc gửi không giữ đĩa của Server mãi mãi.
        while (uploads.size() >= ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL) {
            discard(uploads.keySet().iterator().next());
        }

        // Tạo thư mục tạm (nếu chưa có) và một tệp tạm tên ngẫu nhiên "upload-....part".
        Files.createDirectories(tempDirectory);
        Path file = Files.createTempFile(tempDirectory, "upload-", ".part");
        // Ghi nhận phiên tải với mã UUID mới.
        PendingUpload upload = new PendingUpload(UUID.randomUUID().toString(), filename, extension,
                declaredSize, file);
        uploads.put(upload.id, upload);
        return upload;
    }

    // Huỷ một phiên tải: xoá tệp tạm và gỡ khỏi danh sách.
    private void discard(String uploadId) {
        if (uploads.containsKey(uploadId)) {
            deleteQuietly(uploadId);
            uploads.remove(uploadId);
        }
    }

    // Xoá tệp tạm của phiên tải; lỗi chỉ ghi log.
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
        // Chưa có thư mục: không có gì để xoá.
        if (!Files.isDirectory(tempDirectory)) {
            return;
        }
        // Gom các tệp ".part".
        List<Path> leftovers = new ArrayList<>();
        try (var files = Files.list(tempDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(leftovers::add);
        } catch (IOException error) {
            System.err.println("[UploadManager] Khong doc duoc thu muc tam: " + error.getMessage());
            return;
        }
        // Xoá từng tệp, bỏ qua lỗi.
        for (Path leftover : leftovers) {
            try {
                Files.deleteIfExists(leftover);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Tiến độ sau một khối.
     *
     * @param uploadId      mã phiên tải
     * @param receivedBytes số byte đã nhận
     * @param complete      đã nhận đủ chưa
     */
    public record ChunkResult(String uploadId, long receivedBytes, boolean complete) {
    }

    /** Một tệp đang tải lên. */
    public static final class PendingUpload {
        // Mã phiên tải (UUID).
        private final String id;
        // Tên tệp gốc (đã bỏ đường dẫn).
        private final String filename;
        // Đuôi tệp chữ thường.
        private final String extension;
        // Kích thước Client khai báo.
        private final long declaredSize;
        // Tệp tạm trên đĩa.
        private final Path file;
        // Số byte đã nhận.
        private long receivedBytes;
        // Lần cuối nhận khối (để dọn tệp bỏ dở).
        private long lastActivity = System.currentTimeMillis();

        // Chỉ UploadManager tạo đối tượng này.
        PendingUpload(String id, String filename, String extension, long declaredSize, Path file) {
            this.id = id;
            this.filename = filename;
            this.extension = extension;
            this.declaredSize = declaredSize;
            this.file = file;
        }

        /** @return mã phiên tải */
        public String id() {
            return id;
        }

        /** @return tên tệp gốc */
        public String filename() {
            return filename;
        }

        /** @return đuôi tệp */
        public String extension() {
            return extension;
        }

        /** @return kích thước khai báo */
        public long declaredSize() {
            return declaredSize;
        }

        /** @return tệp tạm trên đĩa */
        public Path file() {
            return file;
        }

        // Đã nhận đủ số byte khai báo chưa.
        boolean isComplete() {
            return receivedBytes == declaredSize;
        }
    }

    /** Lỗi tải lên có câu thông báo hiện được cho người dùng. */
    public static class UploadException extends Exception {
        public UploadException(String message) {
            super(message);
        }
    }
}
