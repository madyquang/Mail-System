// Gói "security" dùng chung: tiện ích bảo mật cho cả Client và Server.
package com.mailsystem.common.security;

// MessageDigest: bộ tính hàm băm (SHA-256...).
import java.security.MessageDigest;
// Lỗi khi máy không hỗ trợ thuật toán băm yêu cầu.
import java.security.NoSuchAlgorithmException;
// Lỗi khi không lấy được dạng nhị phân của chứng chỉ.
import java.security.cert.CertificateEncodingException;
// Chứng chỉ số chuẩn X.509 (chứng chỉ TLS).
import java.security.cert.X509Certificate;

/**
 * Dấu vân tay (fingerprint) SHA-256 của chứng chỉ X.509, dạng "AB:CD:...".
 * Client dùng để ghim (pin) chứng chỉ của Server theo mô hình TOFU; người
 * dùng có thể so sánh với dấu vân tay mà Server in ra khi khởi động.
 */
public final class CertificateFingerprints {

    // Constructor private: lớp tiện ích chỉ có hàm tĩnh, không tạo đối tượng.
    private CertificateFingerprints() {
    }

    /**
     * Tính dấu vân tay SHA-256 của chứng chỉ.
     *
     * @param certificate chứng chỉ cần tính
     * @return chuỗi 32 byte dạng hex in hoa, cách nhau bởi dấu ':' (vd "AB:CD:...")
     */
    public static String sha256(X509Certificate certificate) {
        try {
            // Lấy dạng nhị phân (DER) của chứng chỉ rồi băm bằng SHA-256 -> mảng 32 byte.
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            // Chuẩn bị bộ đệm chuỗi đủ chỗ: mỗi byte cần 2 ký tự hex + 1 dấu ':'.
            StringBuilder text = new StringBuilder(digest.length * 3);
            // Duyệt từng byte của kết quả băm.
            for (int i = 0; i < digest.length; i++) {
                // Từ byte thứ hai trở đi thì chèn dấu ':' phân cách.
                if (i > 0) {
                    text.append(':');
                }
                // Ghi byte dưới dạng 2 chữ số hex in hoa (vd 0x0a -> "0A").
                text.append(String.format("%02X", digest[i]));
            }
            // Trả về chuỗi hoàn chỉnh.
            return text.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException error) {
            // Lỗi hiếm gặp (Java luôn có SHA-256): đổi thành lỗi runtime kèm nguyên nhân gốc.
            throw new IllegalStateException("Không tính được dấu vân tay chứng chỉ", error);
        }
    }
}
