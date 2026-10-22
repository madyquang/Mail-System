package com.mailsystem.client.api;

/** Server trả RESPONSE status = ERROR; message là câu tiếng Việt hiển thị được cho người dùng. */
public class ApiException extends RuntimeException {

    public ApiException(String message) {
        super(message);
    }
}
