package com.mailsystem.common.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/**
 * Dấu vân tay (fingerprint) SHA-256 của chứng chỉ X.509, dạng "AB:CD:...".
 * Client dùng để ghim (pin) chứng chỉ của Server theo mô hình TOFU; người
 * dùng có thể so sánh với dấu vân tay mà Server in ra khi khởi động.
 */
public final class CertificateFingerprints {

    private CertificateFingerprints() {
    }

    public static String sha256(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder text = new StringBuilder(digest.length * 3);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    text.append(':');
                }
                text.append(String.format("%02X", digest[i]));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException error) {
            throw new IllegalStateException("Không tính được dấu vân tay chứng chỉ", error);
        }
    }
}
