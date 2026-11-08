// Gói "network": kết nối mạng tới Server.
package com.mailsystem.client.network;

// IOException; Socket: kết nối TCP.
import java.io.IOException;
import java.net.Socket;

/** Cách mở kết nối tới Server (TLS khi chạy thật; test có thể dùng TCP thường). */
@FunctionalInterface
public interface Connector {

    /** Mở kết nối tới host:port, chờ tối đa timeoutMillis mili giây. */
    Connected connect(String host, int port, int timeoutMillis) throws IOException;

    /** Socket đã kết nối (và đã bắt tay TLS nếu có) kèm mô tả bảo mật để hiển thị. */
    record Connected(Socket socket, SecurityInfo security) {
    }

    /** protocol/cipher/fingerprint null khi kết nối không mã hoá (chỉ dùng trong test). */
    record SecurityInfo(String protocol, String cipherSuite, String fingerprint) {
        /** @return true nếu kết nối có mã hoá TLS */
        public boolean encrypted() {
            return protocol != null;
        }
    }

    /** Kết nối TCP thường, KHÔNG mã hoá. Chỉ dùng cho test tự động. */
    static Connector plain() {
        // Lambda thực hiện hàm connect của interface.
        return (host, port, timeout) -> {
            // Tạo socket chưa kết nối rồi kết nối có giới hạn thời gian.
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), timeout);
            // Không có thông tin bảo mật (không mã hoá).
            return new Connected(socket, new SecurityInfo(null, null, null));
        };
    }
}
