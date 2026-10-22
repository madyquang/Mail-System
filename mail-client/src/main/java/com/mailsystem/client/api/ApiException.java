package com.mailsystem.client.api;

/** Server trả RESPONSE status = ERROR; message là câu tiếng Việt hiển thị được cho người dùng. */
public class ApiException extends RuntimeException {

    private final String code;

    public ApiException(String message) {
        this(null, message);
    }

    /** code: mã lỗi máy đọc được (ErrorCodes), VD TOTP_REQUIRED; null nếu không có. */
    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
