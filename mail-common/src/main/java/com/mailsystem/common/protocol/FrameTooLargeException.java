package com.mailsystem.common.protocol;

import java.io.IOException;

/**
 * Một frame vượt quá giới hạn cho phép. Khi exception này được ném ra, phần
 * còn lại của frame đã bị đọc bỏ tới ký tự '\n', nên luồng dữ liệu vẫn đồng bộ
 * và bên nhận có thể tiếp tục đọc frame kế tiếp.
 */
public class FrameTooLargeException extends IOException {

    public FrameTooLargeException(int maxChars) {
        super("Frame vượt quá giới hạn " + maxChars + " ký tự");
    }
}
