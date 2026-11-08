// Gói "network": kết nối mạng tới Server.
package com.mailsystem.client.network;

// Tính dấu vân tay chứng chỉ.
import com.mailsystem.common.security.CertificateFingerprints;

// Vào/ra tệp, tên máy, mật mã, Base64, chạy tiến trình keytool, KeyManager của TLS.
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

    // Tên khoá trong keystore.
    private static final String ALIAS = "device";

    // KeyManager (null nếu không có chứng chỉ) và dấu vân tay.
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

    /** Nạp / tạo chứng chỉ ở ~/.mailsystem/device.p12; lỗi thì dùng "không có chứng chỉ". */
    public static DeviceIdentity inUserHome() {
        Path dir = Path.of(System.getProperty("user.home"), ".mailsystem");
        try {
            return loadOrCreate(dir.resolve("device.p12"));
        } catch (IOException | GeneralSecurityException error) {
            System.err.println("[TLS] Khong co chung chi thiet bi: " + error.getMessage());
            return none();
        }
    }

    // Nạp keystore; chưa có (hoặc mất tệp mật khẩu) thì tạo mới.
    static DeviceIdentity loadOrCreate(Path keystore) throws IOException, GeneralSecurityException {
        // Tệp mật khẩu nằm cạnh keystore: device.p12.pass.
        Path passFile = keystore.resolveSibling(keystore.getFileName() + ".pass");
        if (!Files.exists(keystore) || !Files.exists(passFile)) {
            // Tạo mới: xoá keystore cũ, sinh mật khẩu ngẫu nhiên, gọi keytool, lưu mật khẩu.
            Files.deleteIfExists(keystore);
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            generate(keystore, password);
            Files.writeString(passFile, password, StandardCharsets.UTF_8);
        }
        // Đọc mật khẩu và mở keystore PKCS#12.
        char[] password = Files.readString(passFile, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        // Lấy chứng chỉ, dựng KeyManager, tính dấu vân tay.
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password);
        return new DeviceIdentity(factory.getKeyManagers(), CertificateFingerprints.sha256(certificate));
    }

    // Gọi keytool tạo cặp khoá EC P-256 + chứng chỉ tự ký dùng cho xác thực client (EKU=clientAuth).
    private static void generate(Path keystore, String password) throws IOException {
        Files.createDirectories(keystore.getParent());
        // Đường dẫn keytool của JDK đang chạy.
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        if (!Files.exists(keytool)) {
            throw new IOException("không tìm thấy keytool trong " + keytool.getParent());
        }
        // Tên máy (chỉ giữ chữ, số, '.', '-') làm tên trong chứng chỉ.
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
        // Chạy keytool, đọc thông báo ra.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            // Quá 60 giây hoặc mã thoát khác 0 là lỗi.
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
