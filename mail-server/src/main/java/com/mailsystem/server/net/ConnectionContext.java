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

    /** Tệp đang tải lên dở dang của tài khoản đang đăng nhập (giữ qua các lần kết nối lại). */
    UploadManager uploads();

    /** Phiên dùng để nhận EVENT (để loại trừ chính phiên này khi thu hồi phiên khác). */
    ClientSession session();

    void signIn(int accountId);

    void signOut();

    /** Hash của token tiếp tục phiên đã cấp cho kết nối này (để huỷ khi LOGOUT). */
    void setResumeTokenHash(String hash);

    /** Dấu vân tay chứng chỉ thiết bị (mutual TLS), null nếu client không gửi chứng chỉ. */
    String deviceFingerprint();
}
