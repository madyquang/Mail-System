package com.mailsystem.client.network;

import com.mailsystem.common.security.CertificateFingerprints;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mô hình TOFU: hỏi lần đầu, ghi nhớ, cảnh báo khi chứng chỉ thay đổi. */
class TofuTrustManagerTest {

    private static X509Certificate certA;
    private static X509Certificate certB;

    @BeforeAll
    static void createCertificates() throws Exception {
        certA = selfSigned("A");
        certB = selfSigned("B");
    }

    /** Sinh chứng chỉ tự ký bằng keytool của JDK đang chạy test. */
    private static X509Certificate selfSigned(String name) throws Exception {
        Path dir = Files.createTempDirectory("tofu-" + name);
        Path keystore = dir.resolve("k.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "k", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=" + name, "-validity", "30", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "secret123", "-keypass", "secret123")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, "secret123".toCharArray());
        }
        return (X509Certificate) store.getCertificate("k");
    }

    @Test
    void firstUseAsksThenRemembersAndWarnsOnChange(@TempDir Path dir) throws Exception {
        KnownServers known = new KnownServers(dir.resolve("known.properties"));
        List<ServerIdentity> asked = new ArrayList<>();

        TofuTrustManager first = new TofuTrustManager("mail.lan", 5000, known, identity -> {
            asked.add(identity);
            return true;
        });
        first.checkServerTrusted(new X509Certificate[] {certA}, "EC");
        assertEquals(1, asked.size());
        assertNull(asked.get(0).previousFingerprint(), "lần đầu: không có dấu vân tay cũ");
        assertEquals(CertificateFingerprints.sha256(certA), known.fingerprint("mail.lan", 5000));

        // Lần sau cùng chứng chỉ: không hỏi nữa (đọc lại từ tệp để chắc đã lưu xuống đĩa).
        KnownServers reloaded = new KnownServers(dir.resolve("known.properties"));
        new TofuTrustManager("MAIL.lan", 5000, reloaded, identity -> {
            throw new AssertionError("không được hỏi lại");
        }).checkServerTrusted(new X509Certificate[] {certA}, "EC");

        // Chứng chỉ đổi: hỏi với cảnh báo; từ chối -> lỗi, không ghi đè dấu vân tay cũ.
        TofuTrustManager changed = new TofuTrustManager("mail.lan", 5000, reloaded, identity -> {
            asked.add(identity);
            return false;
        });
        assertThrows(CertificateException.class, () -> changed.checkServerTrusted(new X509Certificate[] {certB}, "EC"));
        ServerIdentity warning = asked.get(asked.size() - 1);
        assertTrue(warning.changed());
        assertNotNull(warning.previousFingerprint());
        assertEquals(CertificateFingerprints.sha256(certA), reloaded.fingerprint("mail.lan", 5000));
    }

    @Test
    void portIsPartOfIdentity(@TempDir Path dir) throws Exception {
        KnownServers known = new KnownServers(dir.resolve("known.properties"));
        known.trust("mail.lan", 5000, CertificateFingerprints.sha256(certA));
        List<ServerIdentity> asked = new ArrayList<>();
        new TofuTrustManager("mail.lan", 6000, known, identity -> asked.add(identity))
                .checkServerTrusted(new X509Certificate[] {certA}, "EC");
        assertEquals(1, asked.size(), "cổng khác = máy chủ khác, phải hỏi lại");
    }
}
