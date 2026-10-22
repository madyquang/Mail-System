package com.mailsystem.common.protocol;

/** Danh sách sự kiện Server chủ động đẩy về Client (real-time sync). */
public enum EventName {
    NEW_MAIL,           // {mailId, senderEmail, senderName, subject}: có thư mới
    MAIL_READ_UPDATED,  // {entryIds, read}: trạng thái đọc thay đổi
    MAIL_STARRED,       // {entryIds, starred}: gắn/bỏ sao
    MAIL_MOVED,         // {entryIds, folderId}: chuyển thư mục
    MAIL_DELETED,       // {entryIds}: chuyển vào thùng rác / xoá vĩnh viễn
    MAIL_RESTORED,      // {entryIds}: khôi phục từ thùng rác
    READ_RECEIPT,       // {mailId, readerEmail, readerName, readAt}: người nhận vừa mở thư của bạn
    FOLDERS_UPDATED,    // {}: thư mục tự tạo thay đổi
    DRAFTS_UPDATED,     // {}: danh sách thư nháp thay đổi
    SCHEDULED_UPDATED,  // {}: thư chờ gửi thay đổi (đã gửi / huỷ / thêm)
    SCHEDULED_FAILED,   // {outboxId, subject, reason}: thư hẹn giờ không gửi được, đã chuyển thành nháp
    PROFILE_UPDATED,    // {displayName}: tên hiển thị thay đổi
    SESSION_REVOKED     // {reason}: phiên bị thu hồi (đổi mật khẩu, đăng xuất mọi nơi)
}
