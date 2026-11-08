// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

/**
 * Mã lỗi trong ResponseMessage.code (bên cạnh câu thông báo tiếng Việt).
 * Câu thông báo (message) để hiện cho người dùng; mã lỗi (code) để chương trình
 * Client nhận biết và phản ứng tự động (vd hiện ô nhập mã 2FA, quay về màn đăng nhập).
 * Lớp chỉ chứa hằng số nên khai báo final và constructor private (không tạo đối tượng).
 */
public final class ErrorCodes {

    /** Tài khoản bật xác thực 2 lớp: gửi lại LOGIN kèm totpCode. */
    public static final String TOTP_REQUIRED = "TOTP_REQUIRED";
    /** Token tiếp tục phiên hết hạn/không hợp lệ: phải đăng nhập lại bằng mật khẩu. */
    public static final String RESUME_REJECTED = "RESUME_REJECTED";
    /** Thiết bị (chứng chỉ client) chưa được quản trị viên phê duyệt. */
    public static final String DEVICE_NOT_APPROVED = "DEVICE_NOT_APPROVED";
    /** Gửi quá nhanh. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    // Constructor private: không cho tạo đối tượng ErrorCodes (chỉ dùng các hằng số tĩnh).
    private ErrorCodes() {
    }
}
