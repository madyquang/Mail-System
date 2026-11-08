// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// Set: tập hợp không trùng phần tử (danh sách đuôi tệp cho phép).
import java.util.Set;

/**
 * Các giới hạn của giao thức mà Server và Client phải dùng CHUNG một giá trị.
 * Đặt ở mail-common để hai phía không bao giờ lệch nhau.
 */
public final class ProtocolConstants {

    /** Cổng TCP mặc định của Server. */
    public static final int DEFAULT_PORT = 5000;

    /**
     * Độ dài tối đa (tính theo ký tự) của MỘT frame JSON trên đường truyền.
     * Bên nhận từ chối frame dài hơn để một client lỗi/độc hại không thể làm
     * Server hết bộ nhớ bằng một dòng vô tận.
     */
    public static final int MAX_FRAME_CHARS = 1024 * 1024;

    /**
     * Số byte dữ liệu gốc trong một khối upload/download. Sau khi mã hoá
     * Base64 (tăng ~4/3) khối này vẫn nằm gọn trong MAX_FRAME_CHARS.
     */
    public static final int CHUNK_SIZE_BYTES = 512 * 1024;

    // Tối đa 5 tệp đính kèm mỗi thư.
    public static final int MAX_ATTACHMENTS_PER_MAIL = 5;
    // Tổng dung lượng tệp đính kèm mỗi thư tối đa 25 MB (chữ L: kiểu long để không tràn số).
    public static final long MAX_ATTACHMENT_TOTAL_BYTES = 25L * 1024 * 1024;
    // Các đuôi tệp được phép đính kèm (Server còn kiểm tra chữ ký nội dung tệp).
    public static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS = Set.of(
            "pdf", "docx", "pptx", "txt", "jpg", "png", "zip");

    /** Chu kỳ Client gửi PING để phát hiện kết nối "chết" (half-open). */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /** Chỉ dùng TLS 1.3: bỏ hẳn các bộ mã hoá cũ, bắt tay nhanh hơn (1-RTT). */
    public static final String[] TLS_PROTOCOLS = {"TLSv1.3"};

    /** SEND_MAIL {delaySeconds} tối đa (giây) và giới hạn hẹn giờ. Client chính thức gửi ngay, không trễ. */
    public static final int MAX_UNDO_SECONDS = 30;
    // Chỉ được hẹn giờ gửi trong vòng 365 ngày tới.
    public static final int MAX_SCHEDULE_DAYS = 365;

    /** Số thư mục tự tạo tối đa mỗi tài khoản, độ dài tên. */
    public static final int MAX_CUSTOM_FOLDERS = 50;
    // Tên thư mục tối đa 100 ký tự (khớp cột folder_name VARCHAR(100)).
    public static final int MAX_FOLDER_NAME_CHARS = 100;

    /** Số thư tối đa trong một thao tác hàng loạt ({entryIds: [...]}). */
    public static final int MAX_ENTRIES_PER_REQUEST = 500;

    /** Độ dài tối đa của nội dung thư (cột MEDIUMTEXT). */
    public static final int MAX_BODY_CHARS = 100_000;
    // Tiêu đề tối đa 255 ký tự (khớp cột subject VARCHAR(255)).
    public static final int MAX_SUBJECT_CHARS = 255;
    // Tên hiển thị tối đa 100 ký tự (khớp cột display_name VARCHAR(100)).
    public static final int MAX_DISPLAY_NAME_CHARS = 100;

    /** Chính sách mật khẩu: tối thiểu 8 ký tự, có chữ và số; tối đa 128 để chặn DoS băm. */
    public static final int MIN_PASSWORD_LENGTH = 8;
    // Mật khẩu quá dài làm PBKDF2 tốn CPU, nên giới hạn 128 ký tự.
    public static final int MAX_PASSWORD_LENGTH = 128;

    // Constructor private: lớp chỉ chứa hằng số.
    private ProtocolConstants() {
    }
}
