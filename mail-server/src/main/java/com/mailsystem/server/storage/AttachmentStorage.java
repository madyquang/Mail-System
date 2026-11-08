// Gói "storage": nơi lưu nội dung tệp đính kèm trên đĩa.
package com.mailsystem.server.storage;

// ServerConfig: đọc cấu hình thư mục lưu tệp.
import com.mailsystem.server.config.ServerConfig;

// IOException: lỗi vào/ra khi tạo thư mục.
import java.io.IOException;
// Files: tiện ích thao tác tệp/thư mục.
import java.nio.file.Files;
// Path: đường dẫn tệp.
import java.nio.file.Path;
// Locale.ROOT: đổi chữ thường không phụ thuộc ngôn ngữ máy.
import java.util.Locale;
// UUID: mã ngẫu nhiên 128 bit, dùng làm tên tệp.
import java.util.UUID;

/**
 * Nơi lưu nội dung tệp đính kèm trên đĩa Server.
 *
 * Cột attachment.storage_path lưu TÊN tệp (UUID + đuôi), không lưu đường dẫn
 * tuyệt đối, nên có thể chuyển thư mục data/ sang máy khác mà không phải sửa
 * DB. Bản ghi cũ có đường dẫn tuyệt đối vẫn đọc được. Tên tệp do Server sinh
 * (không dùng tên từ Client) nên không thể path traversal.
 */
public final class AttachmentStorage {

    // Constructor private: lớp chỉ có hàm tĩnh.
    private AttachmentStorage() {
    }

    /** @return thư mục lưu tệp đính kèm (cấu hình storage.attachmentsDir) */
    public static Path directory() {
        return ServerConfig.attachmentsDir();
    }

    /** Tên lưu trữ mới, VD "3f2a...e1.pdf". */
    public static String newStorageName(String extension) {
        // Mã UUID ngẫu nhiên + "." + đuôi tệp chữ thường.
        return UUID.randomUUID() + "." + extension.toLowerCase(Locale.ROOT);
    }

    /**
     * Đổi giá trị cột storage_path thành đường dẫn thật trên đĩa.
     *
     * @param storagePath tên tệp (bản ghi mới) hoặc đường dẫn tuyệt đối (bản ghi cũ)
     * @return đường dẫn đầy đủ
     * @throws IllegalArgumentException nếu đường dẫn trỏ ra ngoài thư mục lưu tệp
     */
    public static Path resolve(String storagePath) {
        // Chuyển chuỗi thành Path.
        Path path = Path.of(storagePath);
        // Bản ghi cũ lưu đường dẫn tuyệt đối: dùng nguyên.
        if (path.isAbsolute()) {
            return path;
        }
        // Ghép với thư mục lưu tệp rồi chuẩn hoá (xử lý các ".." nếu có).
        Path resolved = directory().resolve(path).normalize();
        // Sau khi chuẩn hoá mà nằm ngoài thư mục lưu tệp (vd "../../x") thì từ chối.
        if (!resolved.startsWith(directory())) {
            throw new IllegalArgumentException("Đường dẫn tệp đính kèm không hợp lệ");
        }
        return resolved;
    }

    /**
     * Tạo thư mục lưu tệp nếu chưa có.
     *
     * @return đường dẫn thư mục
     */
    public static Path ensureDirectory() throws IOException {
        // createDirectories tạo cả thư mục cha; đã có thì không làm gì.
        return Files.createDirectories(directory());
    }
}
