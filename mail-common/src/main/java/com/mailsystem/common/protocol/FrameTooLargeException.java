// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// IOException: lớp cha của mọi lỗi vào/ra (đọc/ghi socket, tệp).
import java.io.IOException;

/**
 * Một frame vượt quá giới hạn cho phép. Khi exception này được ném ra, phần
 * còn lại của frame đã bị đọc bỏ tới ký tự '\n', nên luồng dữ liệu vẫn đồng bộ
 * và bên nhận có thể tiếp tục đọc frame kế tiếp.
 * (Frame = một dòng JSON kết thúc bằng '\n'; FrameReader ném lỗi này.)
 */
public class FrameTooLargeException extends IOException {

    /**
     * Tạo lỗi kèm câu thông báo có giới hạn ký tự.
     *
     * @param maxChars số ký tự tối đa của một frame (giới hạn đã bị vượt)
     */
    public FrameTooLargeException(int maxChars) {
        // Gọi constructor của IOException với câu thông báo tiếng Việt.
        super("Frame vượt quá giới hạn " + maxChars + " ký tự");
    }
}
