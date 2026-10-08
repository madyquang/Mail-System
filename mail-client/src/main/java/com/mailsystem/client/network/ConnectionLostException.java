package com.mailsystem.client.network;

import java.io.IOException;

/** Request không thể hoàn tất vì chưa kết nối hoặc kết nối tới Server đã mất. */
public class ConnectionLostException extends IOException {

    public ConnectionLostException(String message) {
        super(message);
    }
}
