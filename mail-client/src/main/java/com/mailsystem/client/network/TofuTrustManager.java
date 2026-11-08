// Gói "network": kết nối mạng tới Server.
package com.mailsystem.client.network;

// Tính dấu vân tay chứng chỉ (dùng chung với Server).
import com.mailsystem.common.security.CertificateFingerprints;

// Lỗi chứng chỉ, chứng chỉ X.509, Predicate (hàm trả boolean), TrustManager của TLS.
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

    // Server đang kết nối, danh sách đã tin, hàm hỏi người dùng, dấu vân tay đã chấp nhận.
    private final String host;
    private final int port;
    private final KnownServers knownServers;
    private final Predicate<ServerIdentity> decider;
    private volatile String acceptedFingerprint;

    /** @param decider hỏi người dùng có tin chứng chỉ không (true = tin) */
    public TofuTrustManager(String host, int port, KnownServers knownServers, Predicate<ServerIdentity> decider) {
        this.host = host;
        this.port = port;
        this.knownServers = knownServers;
        this.decider = decider;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        // Server phải gửi chứng chỉ.
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Máy chủ không gửi chứng chỉ.");
        }
        // Chứng chỉ của chính Server là phần tử đầu chuỗi.
        X509Certificate certificate = chain[0];
        certificate.checkValidity(); // hết hạn hoặc chưa có hiệu lực → từ chối
        // Tính dấu vân tay và lấy dấu vân tay đã tin trước đó (nếu có).
        String fingerprint = CertificateFingerprints.sha256(certificate);
        String known = knownServers.fingerprint(host, port);
        // Khớp: tin luôn, không hỏi.
        if (fingerprint.equals(known)) {
            acceptedFingerprint = fingerprint;
            return;
        }
        // Chưa biết hoặc đã thay đổi: hỏi người dùng.
        ServerIdentity identity = new ServerIdentity(host, port,
                certificate.getSubjectX500Principal().getName(), fingerprint, known);
        if (decider == null || !decider.test(identity)) {
            throw new UntrustedServerException(identity.changed()
                    ? "Chứng chỉ của máy chủ đã thay đổi và bạn không chấp nhận. Kết nối đã bị huỷ để bảo vệ bạn."
                    : "Bạn chưa tin chứng chỉ của máy chủ nên kết nối đã bị huỷ.");
        }
        // Người dùng đồng ý: ghi nhớ.
        knownServers.trust(host, port, fingerprint);
        acceptedFingerprint = fingerprint;
    }

    @Override
    // Client không nhận kết nối đến nên không dùng hàm này.
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client không nhận kết nối TLS đến");
    }

    @Override
    // Không chỉ định CA nào.
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /** Dấu vân tay đã chấp nhận ở lần bắt tay gần nhất (hiển thị cho người dùng). */
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
