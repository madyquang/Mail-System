// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

/**
 * Toàn bộ danh sách "lệnh" mà Client có thể gửi lên Server.
 * Xem docs/PROTOCOL.md để biết payload/response tương ứng của từng command.
 * Phần trong {} ở mỗi dòng là các trường chính của payload; dấu ? là trường không bắt buộc.
 */
public enum Command {
    // ---- Kết nối ----
    PING,               // heartbeat: kiểm tra kết nối còn sống, không cần đăng nhập

    // ---- Xác thực & tài khoản ----
    REGISTER,           // {email, displayName, password}: tạo tài khoản mới
    LOGIN,              // {email, password, totpCode?}
    RESUME_SESSION,     // {resumeToken}: đăng nhập lại bằng token sau khi rớt mạng
    LOGOUT,             // đăng xuất phiên hiện tại
    LOGOUT_ALL,         // đăng xuất khỏi mọi thiết bị
    UPDATE_PROFILE,     // đổi tên hiển thị
    CHANGE_PASSWORD,    // đổi mật khẩu (các phiên khác bị thu hồi)
    GET_SETTINGS,       // cài đặt tài khoản (báo đã đọc, 2FA)
    UPDATE_SETTINGS,    // {shareReadReceipts}
    TOTP_SETUP,         // tạo khoá bí mật TOTP mới (chưa bật)
    TOTP_ENABLE,        // {code}: xác nhận mã đầu tiên để bật 2FA
    TOTP_DISABLE,       // {password, code}

    // ---- Thư mục & thư ----
    GET_FOLDERS,        // danh sách thư mục + số thư chưa đọc
    CREATE_FOLDER,      // {name}
    RENAME_FOLDER,      // {folderId, name}
    DELETE_FOLDER,      // {folderId}: thư bên trong chuyển vào Thùng rác
    GET_MAIL_LIST,      // {folderId, threaded?} hoặc {view: "STARRED"}
    GET_MAIL_DETAIL,    // {mailId, entryId?}: nội dung đầy đủ một thư (tự đánh dấu đã đọc)
    GET_THREAD,         // {threadId}: mọi thư trong hội thoại mà mình xem được
    SEARCH_MAIL,        // {keyword, fromFilter, folderId?}: tìm thư
    SUGGEST_CONTACTS,   // {query}: gợi ý người nhận / nhóm khi gõ (autocomplete)
    MARK_READ,          // {entryIds, read?: true|false}
    SET_STARRED,        // {entryIds, starred: true|false}
    MOVE_MAIL,          // {entryIds, folderId}
    DELETE_MAIL,        // {entryIds}: chuyển vào Thùng rác
    DELETE_TRASH_MAIL,  // {entryIds}: xoá vĩnh viễn thư trong Thùng rác
    EMPTY_TRASH,        // xoá vĩnh viễn toàn bộ Thùng rác
    RESTORE_TRASH_MAIL, // {entryIds}: khôi phục thư từ Thùng rác về thư mục cũ

    // ---- Soạn & gửi ----
    UPLOAD_ATTACHMENT_CHUNK, // tải tệp lên theo từng khối trước khi SEND_MAIL
    UPLOAD_STATUS,      // {uploadId}: số byte Server đã nhận (để tải tiếp sau khi rớt mạng)
    DOWNLOAD_ATTACHMENT,// {attachmentId, offset}: tải một khối của tệp đính kèm
    SEND_MAIL,          // + delaySeconds (hoàn tác) hoặc sendAt (hẹn giờ)
    GET_SCHEDULED,      // thư đang chờ gửi
    CANCEL_SCHEDULED,   // {outboxId}: huỷ, trả lại nội dung để soạn tiếp
    SAVE_DRAFT,         // {draftId?, to, cc, bcc, subject, body}: lưu / ghi đè bản nháp
    GET_DRAFTS,         // danh sách bản nháp
    DELETE_DRAFTS,      // {draftIds}: xoá bản nháp

    // ---- Nhóm thư ----
    CREATE_GROUP,       // {groupName, address}: tạo nhóm, người tạo là chủ nhóm
    DELETE_GROUP,       // {groupId}: chủ nhóm xoá nhóm
    ADD_MEMBER,         // {groupId, email}: thêm thành viên
    REMOVE_MEMBER,      // {groupId, email}: xoá thành viên
    LEAVE_GROUP,        // {groupId}: thành viên tự rời nhóm (chủ nhóm không được rời)
    GET_MY_GROUPS       // các nhóm mình là chủ hoặc thành viên
}
