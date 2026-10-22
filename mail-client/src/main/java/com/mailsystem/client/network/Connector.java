package com.mailsystem.client.network;

import java.io.IOException;
import java.net.Socket;

/** Cách mở kết nối tới Server (TLS khi chạy thật; test có thể dùng TCP thường). */
@FunctionalInterface
public interface Connector {

    Connected connect(String host, int port, int timeoutMillis) throws IOException;

    /** Socket đã kết nối (và đã bắt tay TLS nếu có) kèm mô tả bảo mật để hiển thị. */
    record Connected(Socket socket, SecurityInfo security) {
    }

    /** protocol/cipher/fingerprint null khi kết nối không mã hoá (chỉ dùng trong test). */
    record SecurityInfo(String protocol, String cipherSuite, String fingerprint) {
        public boolean encrypted() {
            return protocol != null;
        }
    }

    /** Kết nối TCP thường, KHÔNG mã hoá. Chỉ dùng cho test tự động. */
    static Connector plain() {
        return (host, port, timeout) -> {
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), timeout);
            return new Connected(socket, new SecurityInfo(null, null, null));
        };
    }
}
