package com.mailsystem.common.protocol;

/**
 * Toàn bộ danh sách "lệnh" mà Client có thể gửi lên Server.
 * Xem tài liệu Protocol để biết payload/response tương ứng của từng command.
 */
public enum Command {
    // ---- Auth ----
    REGISTER,
    LOGIN,
    LOGOUT,

    // ---- Kết nối ----
    PING,               // heartbeat: kiểm tra kết nối còn sống, không cần đăng nhập

    // ---- Folder & Mail ----
    GET_FOLDERS,
    GET_MAIL_LIST,
    GET_MAIL_DETAIL,
    DOWNLOAD_ATTACHMENT,
    UPLOAD_ATTACHMENT_CHUNK, // tải tệp lên theo từng khối trước khi SEND_MAIL
    SEND_MAIL,
    MARK_READ,
    DELETE_MAIL,
    DELETE_TRASH_MAIL,
    EMPTY_TRASH,
    RESTORE_TRASH_MAIL,
    SEARCH_MAIL,

    // ---- Mail Group ----
    CREATE_GROUP,
    DELETE_GROUP,
    ADD_MEMBER,
    REMOVE_MEMBER,
    LEAVE_GROUP,
    GET_MY_GROUPS
}
