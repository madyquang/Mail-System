package com.mailsystem.server.storage;

import com.mailsystem.server.config.ServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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

    private AttachmentStorage() {
    }

    public static Path directory() {
        return ServerConfig.attachmentsDir();
    }

    /** Tên lưu trữ mới, VD "3f2a...e1.pdf". */
    public static String newStorageName(String extension) {
        return UUID.randomUUID() + "." + extension.toLowerCase(Locale.ROOT);
    }

    public static Path resolve(String storagePath) {
        Path path = Path.of(storagePath);
        if (path.isAbsolute()) {
            return path;
        }
        Path resolved = directory().resolve(path).normalize();
        if (!resolved.startsWith(directory())) {
            throw new IllegalArgumentException("Đường dẫn tệp đính kèm không hợp lệ");
        }
        return resolved;
    }

    public static Path ensureDirectory() throws IOException {
        return Files.createDirectories(directory());
    }
}
