package com.mailsystem.common.protocol;

/** Danh sách sự kiện Server chủ động đẩy về Client (real-time sync). */
public enum EventName {
    NEW_MAIL,           // {mailId, senderEmail, senderName, subject}: có thư mới
    MAIL_READ_UPDATED,  // {entryIds, read}: trạng thái đọc thay đổi
    MAIL_STARRED,       // {entryIds, starred}: gắn/bỏ sao
    MAIL_DELETED,       // {entryIds}: chuyển vào thùng rác / xoá vĩnh viễn
    MAIL_RESTORED,      // {entryIds}: khôi phục từ thùng rác
    DRAFTS_UPDATED,     // {}: danh sách thư nháp thay đổi
    PROFILE_UPDATED,    // {displayName}: tên hiển thị thay đổi
    SESSION_REVOKED     // {reason}: phiên bị thu hồi (đổi mật khẩu, đăng xuất mọi nơi)
}
