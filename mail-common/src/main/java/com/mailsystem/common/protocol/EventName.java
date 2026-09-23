package com.mailsystem.common.protocol;

/** Danh sách sự kiện Server chủ động đẩy về Client (real-time sync). */
public enum EventName {
    NEW_MAIL,           // Có thư mới vào folder client đang mở/theo dõi
    MAIL_READ_UPDATED,  // Thư bị đánh dấu đã đọc từ thiết bị/session khác
    MAIL_DELETED        // Thư bị xóa từ thiết bị/session khác
}
