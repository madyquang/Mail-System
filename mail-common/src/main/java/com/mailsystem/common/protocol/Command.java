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

    // ---- Folder & Mail ----
    GET_FOLDERS,
    GET_MAIL_LIST,
    GET_MAIL_DETAIL,
    SEND_MAIL,
    MARK_READ,
    DELETE_MAIL,
    SEARCH_MAIL,

    // ---- Mail Group ----
    CREATE_GROUP,
    DELETE_GROUP,
    ADD_MEMBER,
    REMOVE_MEMBER,
    LEAVE_GROUP,
    GET_MY_GROUPS
}
