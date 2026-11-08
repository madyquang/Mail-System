// Gói "api": các hàm gọi Server theo từng chức năng.
package com.mailsystem.client.api;

/** Server trả RESPONSE status = ERROR; message là câu tiếng Việt hiển thị được cho người dùng. */
public class ApiException extends RuntimeException {

    // Mã lỗi máy đọc được (null nếu Server không gửi).
    private final String code;

    /** Lỗi chỉ có câu thông báo. */
    public ApiException(String message) {
        this(null, message);
    }

    /** code: mã lỗi máy đọc được (ErrorCodes), VD TOTP_REQUIRED; null nếu không có. */
    public ApiException(String code, String message) {
        // Câu thông báo lưu ở lớp cha (getMessage()).
        super(message);
        this.code = code;
    }

    /** @return mã lỗi hoặc null */
    public String code() {
        return code;
    }
}
