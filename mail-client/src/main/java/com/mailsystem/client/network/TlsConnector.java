package com.mailsystem.client.network;

import com.mailsystem.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * Mở kết nối TLS 1.3 tới Server và xác thực chứng chỉ theo TOFU.
 *
 * Thứ tự: TCP connect (có timeout) → bắt tay TLS (trao đổi khoá ECDHE, Server
 * trình chứng chỉ, TofuTrustManager kiểm tra) → từ đây mọi frame JSON đi trong
 * kênh đã mã hoá và có kiểm tra toàn vẹn.
 */
public class TlsConnector implements Connector {

    private static final int HANDSHAKE_TIMEOUT_MILLIS = 120_000; // gồm thời gian người dùng đọc hộp thoại xác nhận

    private final KnownServers knownServers;
    private volatile Predicate<ServerIdentity> decider;

    public TlsConnector(KnownServers knownServers) {
        this.knownServers = knownServers;
    }

    /** Hàm hỏi người dùng khi gặp chứng chỉ lạ/đã đổi (được gọi trên thread nền, được phép chặn). */
    public void setDecider(Predicate<ServerIdentity> decider) {
        this.decider = decider;
    }

    public KnownServers knownServers() {
        return knownServers;
    }

    @Override
    public Connected connect(String host, int port, int timeoutMillis) throws IOException {
        TofuTrustManager trustManager = new TofuTrustManager(host, port, knownServers, decider);
        SSLSocket socket;
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[] {trustManager}, new SecureRandom());
            socket = (SSLSocket) context.getSocketFactory().createSocket();
        } catch (GeneralSecurityException error) {
            throw new IOException("Không khởi tạo được TLS: " + error.getMessage(), error);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setEnabledProtocols(ProtocolConstants.TLS_PROTOCOLS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            socket.startHandshake();
            socket.setSoTimeout(0); // sau bắt tay: chờ frame vô hạn, heartbeat lo phát hiện kết nối chết
            return new Connected(socket, new SecurityInfo(socket.getSession().getProtocol(),
                    socket.getSession().getCipherSuite(), trustManager.acceptedFingerprint()));
        } catch (IOException error) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw translate(error);
        }
    }

    /** Đổi lỗi TLS kỹ thuật thành thông báo người dùng hiểu được. */
    private static IOException translate(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TofuTrustManager.UntrustedServerException untrusted) {
                return new ConnectionLostException(untrusted.getMessage());
            }
        }
        if (error instanceof SSLHandshakeException || error instanceof SSLException) {
            return new ConnectionLostException("Bắt tay TLS thất bại (máy chủ không hỗ trợ TLS 1.3 hoặc không phải "
                    + "Mail System): " + error.getMessage());
        }
        return error;
    }
}
