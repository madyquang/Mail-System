package com.mailsystem.common.protocol;

/**
 * Toàn bộ danh sách "lệnh" mà Client có thể gửi lên Server.
 * Xem docs/PROTOCOL.md để biết payload/response tương ứng của từng command.
 */
public enum Command {
    // ---- Kết nối ----
    PING,               // heartbeat: kiểm tra kết nối còn sống, không cần đăng nhập

    // ---- Xác thực & tài khoản ----
    REGISTER,
    LOGIN,              // {email, password, totpCode?}
    RESUME_SESSION,     // {resumeToken}: đăng nhập lại bằng token sau khi rớt mạng
    LOGOUT,
    LOGOUT_ALL,         // đăng xuất khỏi mọi thiết bị
    UPDATE_PROFILE,     // đổi tên hiển thị
    CHANGE_PASSWORD,    // đổi mật khẩu (các phiên khác bị thu hồi)
    GET_SETTINGS,       // cài đặt tài khoản (báo đã đọc, 2FA)
    UPDATE_SETTINGS,    // {shareReadReceipts}
    TOTP_SETUP,         // tạo khoá bí mật TOTP mới (chưa bật)
    TOTP_ENABLE,        // {code}: xác nhận mã đầu tiên để bật 2FA
    TOTP_DISABLE,       // {password, code}

    // ---- Thư mục & thư ----
    GET_FOLDERS,
    CREATE_FOLDER,      // {name}
    RENAME_FOLDER,      // {folderId, name}
    DELETE_FOLDER,      // {folderId}: thư bên trong chuyển vào Thùng rác
    GET_MAIL_LIST,      // {folderId, threaded?} hoặc {view: "STARRED"}
    GET_MAIL_DETAIL,
    GET_THREAD,         // {threadId}: mọi thư trong hội thoại mà mình xem được
    SEARCH_MAIL,
    SUGGEST_CONTACTS,   // {query}: gợi ý người nhận / nhóm khi gõ (autocomplete)
    MARK_READ,          // {entryIds, read?: true|false}
    SET_STARRED,        // {entryIds, starred: true|false}
    MOVE_MAIL,          // {entryIds, folderId}
    DELETE_MAIL,
    DELETE_TRASH_MAIL,
    EMPTY_TRASH,
    RESTORE_TRASH_MAIL,

    // ---- Soạn & gửi ----
    UPLOAD_ATTACHMENT_CHUNK, // tải tệp lên theo từng khối trước khi SEND_MAIL
    UPLOAD_STATUS,      // {uploadId}: số byte Server đã nhận (để tải tiếp sau khi rớt mạng)
    DOWNLOAD_ATTACHMENT,
    SEND_MAIL,          // + delaySeconds (hoàn tác) hoặc sendAt (hẹn giờ)
    GET_SCHEDULED,      // thư đang chờ gửi
    CANCEL_SCHEDULED,   // {outboxId}: huỷ, trả lại nội dung để soạn tiếp
    SAVE_DRAFT,
    GET_DRAFTS,
    DELETE_DRAFTS,

    // ---- Nhóm thư ----
    CREATE_GROUP,
    DELETE_GROUP,
    ADD_MEMBER,
    REMOVE_MEMBER,
    LEAVE_GROUP,
    GET_MY_GROUPS
}
