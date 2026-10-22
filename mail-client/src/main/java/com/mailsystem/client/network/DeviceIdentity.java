package com.mailsystem.client.network;

import com.mailsystem.common.security.CertificateFingerprints;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;

/**
 * Chứng chỉ THIẾT BỊ cho mutual TLS: mỗi máy cài Client có một cặp khoá EC
 * P-256 riêng (~/.mailsystem/device.p12). Khi bắt tay TLS, Server xin chứng chỉ
 * client (CertificateRequest) và Client trình chứng chỉ này, chứng minh sở hữu
 * khoá riêng bằng chữ ký CertificateVerify. Server nhận diện máy qua dấu vân tay
 * SHA-256; ở chế độ "need" chỉ máy đã được quản trị viên phê duyệt mới vào được.
 *
 * Khoá riêng không bao giờ rời máy. Nếu không tạo được (môi trường thiếu
 * keytool) thì Client kết nối không có chứng chỉ (Server ở chế độ "want" vẫn chấp nhận).
 */
public final class DeviceIdentity {

    private static final String ALIAS = "device";

    private final KeyManager[] keyManagers;
    private final String fingerprint;

    private DeviceIdentity(KeyManager[] keyManagers, String fingerprint) {
        this.keyManagers = keyManagers;
        this.fingerprint = fingerprint;
    }

    /** Thiết bị không có chứng chỉ. */
    static DeviceIdentity none() {
        return new DeviceIdentity(null, null);
    }

    public static DeviceIdentity inUserHome() {
        Path dir = Path.of(System.getProperty("user.home"), ".mailsystem");
        try {
            return loadOrCreate(dir.resolve("device.p12"));
        } catch (IOException | GeneralSecurityException error) {
            System.err.println("[TLS] Khong co chung chi thiet bi: " + error.getMessage());
            return none();
        }
    }

    static DeviceIdentity loadOrCreate(Path keystore) throws IOException, GeneralSecurityException {
        Path passFile = keystore.resolveSibling(keystore.getFileName() + ".pass");
        if (!Files.exists(keystore) || !Files.exists(passFile)) {
            Files.deleteIfExists(keystore);
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            generate(keystore, password);
            Files.writeString(passFile, password, StandardCharsets.UTF_8);
        }
        char[] password = Files.readString(passFile, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password);
        return new DeviceIdentity(factory.getKeyManagers(), CertificateFingerprints.sha256(certificate));
    }

    private static void generate(Path keystore, String password) throws IOException {
        Files.createDirectories(keystore.getParent());
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        if (!Files.exists(keytool)) {
            throw new IOException("không tìm thấy keytool trong " + keytool.getParent());
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9.\\-]", "");
        } catch (IOException error) {
            host = "unknown";
        }
        List<String> command = List.of(keytool.toString(), "-genkeypair", "-noprompt",
                "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650", "-dname", "CN=" + (host.isEmpty() ? "unknown" : host) + ", O=Mail System Device",
                "-ext", "EKU=clientAuth",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", password, "-keypass", password);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool lỗi: " + output.trim());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Bị ngắt khi tạo chứng chỉ thiết bị", error);
        }
    }

    /** KeyManager trình chứng chỉ thiết bị khi Server yêu cầu; null nếu không có. */
    public KeyManager[] keyManagers() {
        return keyManagers;
    }

    /** Dấu vân tay để người dùng gửi quản trị viên phê duyệt; null nếu không có. */
    public String fingerprint() {
        return fingerprint;
    }
}
