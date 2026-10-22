package com.mailsystem.common.protocol;

/** Mã lỗi trong ResponseMessage.code (bên cạnh câu thông báo tiếng Việt). */
public final class ErrorCodes {

    /** Tài khoản bật xác thực 2 lớp: gửi lại LOGIN kèm totpCode. */
    public static final String TOTP_REQUIRED = "TOTP_REQUIRED";
    /** Token tiếp tục phiên hết hạn/không hợp lệ: phải đăng nhập lại bằng mật khẩu. */
    public static final String RESUME_REJECTED = "RESUME_REJECTED";
    /** Thiết bị (chứng chỉ client) chưa được quản trị viên phê duyệt. */
    public static final String DEVICE_NOT_APPROVED = "DEVICE_NOT_APPROVED";
    /** Gửi quá nhanh. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    private ErrorCodes() {
    }
}
