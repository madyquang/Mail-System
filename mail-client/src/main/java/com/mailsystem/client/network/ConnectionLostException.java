// Gói "network": kết nối mạng tới Server (TLS, đọc/ghi frame, tin cậy chứng chỉ).
package com.mailsystem.client.network;

// IOException: lớp cha của lỗi vào/ra.
import java.io.IOException;

/** Request không thể hoàn tất vì chưa kết nối hoặc kết nối tới Server đã mất. */
public class ConnectionLostException extends IOException {

    /** @param message câu báo hiện cho người dùng */
    public ConnectionLostException(String message) {
        // Gọi constructor của IOException.
        super(message);
    }
}
