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
    LOGIN,
    LOGOUT,
    LOGOUT_ALL,         // đăng xuất khỏi mọi thiết bị
    UPDATE_PROFILE,     // đổi tên hiển thị
    CHANGE_PASSWORD,    // đổi mật khẩu (các phiên khác bị thu hồi)

    // ---- Thư mục & thư ----
    GET_FOLDERS,
    GET_MAIL_LIST,      // {folderId} hoặc {view: "STARRED"}
    GET_MAIL_DETAIL,
    SEARCH_MAIL,
    MARK_READ,          // {entryIds, read?: true|false}
    SET_STARRED,        // {entryIds, starred: true|false}
    DELETE_MAIL,
    DELETE_TRASH_MAIL,
    EMPTY_TRASH,
    RESTORE_TRASH_MAIL,

    // ---- Soạn & gửi ----
    UPLOAD_ATTACHMENT_CHUNK, // tải tệp lên theo từng khối trước khi SEND_MAIL
    DOWNLOAD_ATTACHMENT,
    SEND_MAIL,
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
