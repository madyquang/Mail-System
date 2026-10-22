package com.mailsystem.server.net;

import com.mailsystem.server.session.ClientSession;
import com.mailsystem.server.transfer.UploadManager;

/**
 * Trạng thái của MỘT kết nối TCP mà handler của từng command được phép dùng.
 * Trạng thái đăng nhập gắn với kết nối (stateful protocol): request sau không
 * cần gửi lại mật khẩu hay token.
 */
public interface ConnectionContext {

    /** null nếu kết nối chưa đăng nhập. */
    Integer accountId();

    /** Địa chỉ IP phía client (dùng cho chống dò mật khẩu, log bảo mật). */
    String remoteIp();

    /** Tệp đính kèm đang tải lên dở dang của kết nối này. */
    UploadManager uploads();

    /** Phiên dùng để nhận EVENT (để loại trừ chính phiên này khi thu hồi phiên khác). */
    ClientSession session();

    void signIn(int accountId);

    void signOut();
}
