package com.mailsystem.common.protocol;

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

    public static final int MAX_ATTACHMENTS_PER_MAIL = 5;
    public static final long MAX_ATTACHMENT_TOTAL_BYTES = 25L * 1024 * 1024;
    public static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS = Set.of(
            "pdf", "docx", "pptx", "txt", "jpg", "png", "zip");

    /** Chu kỳ Client gửi PING để phát hiện kết nối "chết" (half-open). */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /** Chỉ dùng TLS 1.3: bỏ hẳn các bộ mã hoá cũ, bắt tay nhanh hơn (1-RTT). */
    public static final String[] TLS_PROTOCOLS = {"TLSv1.3"};

    /** Thời gian chờ để hoàn tác gửi thư (giây) và giới hạn hẹn giờ. */
    public static final int UNDO_SEND_SECONDS = 8;
    public static final int MAX_UNDO_SECONDS = 30;
    public static final int MAX_SCHEDULE_DAYS = 365;

    /** Số thư mục tự tạo tối đa mỗi tài khoản, độ dài tên. */
    public static final int MAX_CUSTOM_FOLDERS = 50;
    public static final int MAX_FOLDER_NAME_CHARS = 100;

    /** Số thư tối đa trong một thao tác hàng loạt ({entryIds: [...]}). */
    public static final int MAX_ENTRIES_PER_REQUEST = 500;

    /** Độ dài tối đa của nội dung thư (cột MEDIUMTEXT). */
    public static final int MAX_BODY_CHARS = 100_000;
    public static final int MAX_SUBJECT_CHARS = 255;
    public static final int MAX_DISPLAY_NAME_CHARS = 100;

    /** Chính sách mật khẩu: tối thiểu 8 ký tự, có chữ và số; tối đa 128 để chặn DoS băm. */
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private ProtocolConstants() {
    }
}
