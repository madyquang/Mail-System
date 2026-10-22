package com.mailsystem.client.network;

import com.mailsystem.common.security.CertificateFingerprints;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.function.Predicate;
import javax.net.ssl.X509TrustManager;

/**
 * Tin chứng chỉ máy chủ theo mô hình TOFU (Trust On First Use), giống SSH:
 * <ul>
 *   <li>Đã biết và khớp dấu vân tay → tin, không hỏi.</li>
 *   <li>Chưa biết → hỏi người dùng (hiện dấu vân tay để đối chiếu với Server); đồng ý thì ghi nhớ.</li>
 *   <li>Đã biết nhưng KHÁC → cảnh báo nghiêm trọng (có thể bị tấn công man-in-the-middle).</li>
 * </ul>
 * Phương thức chạy trong lúc bắt tay TLS (trên thread nền của kết nối), nên
 * decider được phép chặn để chờ người dùng trả lời.
 */
public class TofuTrustManager implements X509TrustManager {

    private final String host;
    private final int port;
    private final KnownServers knownServers;
    private final Predicate<ServerIdentity> decider;
    private volatile String acceptedFingerprint;

    public TofuTrustManager(String host, int port, KnownServers knownServers, Predicate<ServerIdentity> decider) {
        this.host = host;
        this.port = port;
        this.knownServers = knownServers;
        this.decider = decider;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Máy chủ không gửi chứng chỉ.");
        }
        X509Certificate certificate = chain[0];
        certificate.checkValidity(); // hết hạn hoặc chưa có hiệu lực → từ chối
        String fingerprint = CertificateFingerprints.sha256(certificate);
        String known = knownServers.fingerprint(host, port);
        if (fingerprint.equals(known)) {
            acceptedFingerprint = fingerprint;
            return;
        }
        ServerIdentity identity = new ServerIdentity(host, port,
                certificate.getSubjectX500Principal().getName(), fingerprint, known);
        if (decider == null || !decider.test(identity)) {
            throw new UntrustedServerException(identity.changed()
                    ? "Chứng chỉ của máy chủ đã thay đổi và bạn không chấp nhận. Kết nối đã bị huỷ để bảo vệ bạn."
                    : "Bạn chưa tin chứng chỉ của máy chủ nên kết nối đã bị huỷ.");
        }
        knownServers.trust(host, port, fingerprint);
        acceptedFingerprint = fingerprint;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client không nhận kết nối TLS đến");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    public String acceptedFingerprint() {
        return acceptedFingerprint;
    }

    /** Người dùng từ chối chứng chỉ: thông báo hiển thị thẳng cho người dùng. */
    public static class UntrustedServerException extends CertificateException {
        public UntrustedServerException(String message) {
            super(message);
        }
    }
}
