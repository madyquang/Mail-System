package com.mailsystem.server.security;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Tạo SSLContext cho Server từ keystore PKCS#12.
 *
 * Lần chạy đầu tiên chưa có keystore: Server tự sinh cặp khoá EC P-256 và
 * chứng chỉ tự ký (self-signed) bằng công cụ keytool đi kèm JDK. Mật khẩu
 * keystore ngẫu nhiên được lưu cạnh keystore (server.p12.pass). Vì chứng chỉ tự
 * ký không được CA nào xác nhận, Client tin nó theo mô hình TOFU (Trust On First
 * Use): lần đầu hiển thị dấu vân tay để người dùng xác nhận, các lần sau tự so
 * khớp và cảnh báo nếu chứng chỉ bị thay (có thể là tấn công man-in-the-middle).
 */
public final class TlsContextFactory {

    private static final String ALIAS = "mailserver";

    private TlsContextFactory() {
    }

    public record ServerTls(SSLContext context, X509Certificate certificate, String fingerprint) {
    }

    public static ServerTls loadOrCreate(Path keystore, String configuredPassword)
            throws IOException, GeneralSecurityException {
        char[] password = resolvePassword(keystore, configuredPassword);
        if (!Files.isRegularFile(keystore)) {
            generate(keystore, password);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        X509Certificate certificate = firstCertificate(store);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, password);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        return new ServerTls(context, certificate, CertificateFingerprints.sha256(certificate));
    }

    private static char[] resolvePassword(Path keystore, String configured) throws IOException {
        if (configured != null && !configured.isBlank()) {
            return configured.toCharArray();
        }
        Path passwordFile = keystore.resolveSibling(keystore.getFileName() + ".pass");
        if (Files.isRegularFile(passwordFile)) {
            return Files.readString(passwordFile, StandardCharsets.UTF_8).trim().toCharArray();
        }
        if (Files.isRegularFile(keystore)) {
            throw new IOException("Có keystore " + keystore + " nhưng thiếu mật khẩu (tls.keystorePassword hoặc "
                    + passwordFile.getFileName() + ")");
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Files.createDirectories(passwordFile.getParent());
        Files.writeString(passwordFile, generated, StandardCharsets.UTF_8);
        restrictToOwner(passwordFile);
        return generated.toCharArray();
    }

    /** Gọi keytool của chính JDK đang chạy (API Java chuẩn không có hàm tạo chứng chỉ). */
    private static void generate(Path keystore, char[] password) throws IOException {
        Files.createDirectories(keystore.getParent());
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        String pass = new String(password);
        List<String> command = new ArrayList<>(List.of(keytool, "-genkeypair", "-noprompt",
                "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650", "-dname", "CN=Mail System Server, O=Mail System",
                "-ext", "SAN=" + subjectAlternativeNames(),
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", pass, "-keypass", pass));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool không tạo được chứng chỉ: " + output.trim());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Bị ngắt khi tạo chứng chỉ", error);
        }
        restrictToOwner(keystore);
        System.out.println("[TLS] Da tao chung chi tu ky moi: " + keystore);
    }

    /** SAN gồm localhost và tên máy, để client dùng các tên này đều thấy cùng chứng chỉ. */
    private static String subjectAlternativeNames() {
        Set<String> names = new LinkedHashSet<>(List.of("dns:localhost", "ip:127.0.0.1"));
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && host.matches("[A-Za-z0-9.-]+")) {
                names.add("dns:" + host);
            }
        } catch (IOException ignored) {
        }
        return String.join(",", names);
    }

    private static X509Certificate firstCertificate(KeyStore store) throws GeneralSecurityException {
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (store.getCertificate(alias) instanceof X509Certificate certificate) {
                return certificate;
            }
        }
        throw new GeneralSecurityException("Keystore không chứa chứng chỉ X.509");
    }

    private static void restrictToOwner(Path file) {
        // Best effort: chỉ chủ sở hữu được đọc tệp khoá (POSIX); Windows bỏ qua.
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
