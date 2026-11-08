// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// Tính dấu vân tay chứng chỉ (dùng chung với Client).
import com.mailsystem.common.security.CertificateFingerprints;

// Vào/ra tệp, tên máy, bảng mã.
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
// Mật mã: lỗi chung, kho khoá, sinh ngẫu nhiên, chứng chỉ X.509.
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
// Tiện ích tập hợp, Base64, thời gian chờ.
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
// KeyManagerFactory: đưa khoá riêng + chứng chỉ vào TLS; SSLContext: cấu hình TLS.
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

    // Tên (alias) của khoá trong keystore.
    private static final String ALIAS = "mailserver";

    // Constructor private: lớp chỉ có hàm tĩnh.
    private TlsContextFactory() {
    }

    /**
     * Kết quả: cấu hình TLS, chứng chỉ, dấu vân tay.
     *
     * @param context     SSLContext để tạo ServerSocket TLS
     * @param certificate chứng chỉ của Server
     * @param fingerprint dấu vân tay SHA-256 (in ra cho người dùng đối chiếu)
     */
    public record ServerTls(SSLContext context, X509Certificate certificate, String fingerprint) {
    }

    /**
     * Nạp keystore (tạo mới nếu chưa có) và dựng SSLContext TLS 1.3.
     *
     * @param keystore           đường dẫn tệp .p12
     * @param configuredPassword mật khẩu trong cấu hình (trống thì dùng tệp .pass)
     */
    public static ServerTls loadOrCreate(Path keystore, String configuredPassword)
            throws IOException, GeneralSecurityException {
        // Xác định mật khẩu keystore.
        char[] password = resolvePassword(keystore, configuredPassword);
        // Chưa có tệp keystore: tạo cặp khoá + chứng chỉ tự ký.
        if (!Files.isRegularFile(keystore)) {
            generate(keystore, password);
        }
        // Mở keystore định dạng PKCS#12.
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        // Lấy chứng chỉ đầu tiên trong keystore.
        X509Certificate certificate = firstCertificate(store);
        // Bộ quản lý khoá: TLS dùng khoá riêng trong keystore để chứng minh danh tính Server.
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, password);
        // Cấu hình TLS phiên bản 1.3.
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        // Chứng chỉ CLIENT (mutual TLS) là tự ký nên không kiểm theo CA: chấp nhận ở bước bắt tay,
        // rồi ClientHandler đối chiếu dấu vân tay với danh sách thiết bị đã duyệt (DeviceRegistry).
        context.init(keyManagers.getKeyManagers(), new javax.net.ssl.TrustManager[] {new ClientCertificateRecorder()},
                new SecureRandom());
        // Trả kết quả kèm dấu vân tay.
        return new ServerTls(context, certificate, CertificateFingerprints.sha256(certificate));
    }

    // Tìm mật khẩu keystore: từ cấu hình, từ tệp .pass, hoặc sinh mới (lần chạy đầu).
    private static char[] resolvePassword(Path keystore, String configured) throws IOException {
        // Có trong cấu hình thì dùng.
        if (configured != null && !configured.isBlank()) {
            return configured.toCharArray();
        }
        // Tệp mật khẩu nằm cạnh keystore: "server.p12.pass".
        Path passwordFile = keystore.resolveSibling(keystore.getFileName() + ".pass");
        if (Files.isRegularFile(passwordFile)) {
            return Files.readString(passwordFile, StandardCharsets.UTF_8).trim().toCharArray();
        }
        // Có keystore mà không có mật khẩu ở đâu cả: không mở được.
        if (Files.isRegularFile(keystore)) {
            throw new IOException("Có keystore " + keystore + " nhưng thiếu mật khẩu (tls.keystorePassword hoặc "
                    + passwordFile.getFileName() + ")");
        }
        // Lần chạy đầu: sinh mật khẩu ngẫu nhiên 24 byte, mã hoá Base64 URL-safe.
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        // Ghi ra tệp .pass và giới hạn quyền đọc.
        Files.createDirectories(passwordFile.getParent());
        Files.writeString(passwordFile, generated, StandardCharsets.UTF_8);
        restrictToOwner(passwordFile);
        return generated.toCharArray();
    }

    /** Gọi keytool của chính JDK đang chạy (API Java chuẩn không có hàm tạo chứng chỉ). */
    private static void generate(Path keystore, char[] password) throws IOException {
        Files.createDirectories(keystore.getParent());
        // Đường dẫn keytool: <java.home>/bin/keytool(.exe trên Windows).
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        String pass = new String(password);
        // Lệnh: tạo cặp khoá EC đường cong secp256r1 (P-256), ký SHA256withECDSA, hạn 3650 ngày (~10 năm),
        // tên chủ thể, danh sách tên thay thế (SAN), lưu dạng PKCS12.
        List<String> command = new ArrayList<>(List.of(keytool, "-genkeypair", "-noprompt",
                "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650", "-dname", "CN=Mail System Server, O=Mail System",
                "-ext", "SAN=" + subjectAlternativeNames(),
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", pass, "-keypass", pass));
        // Chạy keytool, gộp luồng lỗi vào luồng ra để đọc thông báo.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            // Chờ tối đa 60 giây; quá giờ hoặc mã thoát khác 0 là lỗi.
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool không tạo được chứng chỉ: " + output.trim());
            }
        } catch (InterruptedException error) {
            // Giữ lại cờ "bị ngắt" của thread rồi báo lỗi.
            Thread.currentThread().interrupt();
            throw new IOException("Bị ngắt khi tạo chứng chỉ", error);
        }
        // Giới hạn quyền đọc tệp khoá.
        restrictToOwner(keystore);
        System.out.println("[TLS] Da tao chung chi tu ky moi: " + keystore);
    }

    /** SAN gồm localhost và tên máy, để client dùng các tên này đều thấy cùng chứng chỉ. */
    private static String subjectAlternativeNames() {
        // Tập giữ thứ tự, bỏ trùng.
        Set<String> names = new LinkedHashSet<>(List.of("dns:localhost", "ip:127.0.0.1"));
        try {
            // Thêm tên máy nếu chỉ gồm ký tự hợp lệ.
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && host.matches("[A-Za-z0-9.-]+")) {
                names.add("dns:" + host);
            }
        } catch (IOException ignored) {
            // Không lấy được tên máy: chỉ dùng localhost.
        }
        // Dạng "dns:localhost,ip:127.0.0.1,dns:TEN-MAY".
        return String.join(",", names);
    }

    // Chứng chỉ X.509 đầu tiên trong keystore.
    private static X509Certificate firstCertificate(KeyStore store) throws GeneralSecurityException {
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            // Pattern matching: nếu là X509Certificate thì gán vào biến certificate.
            if (store.getCertificate(alias) instanceof X509Certificate certificate) {
                return certificate;
            }
        }
        throw new GeneralSecurityException("Keystore không chứa chứng chỉ X.509");
    }

    /** Chỉ kiểm hạn dùng của chứng chỉ client; quyết định cho phép nằm ở DeviceRegistry. */
    private static final class ClientCertificateRecorder implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            // Phải có chứng chỉ.
            if (chain == null || chain.length == 0) {
                throw new java.security.cert.CertificateException("Thiếu chứng chỉ client");
            }
            // Chứng chỉ còn hạn (ném lỗi nếu hết hạn / chưa tới hạn).
            chain[0].checkValidity();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            // Server không bao giờ đóng vai client TLS.
            throw new java.security.cert.CertificateException("Server không đóng vai client TLS");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            // Không chỉ định CA nào: Client gửi chứng chỉ tự ký của mình.
            return new X509Certificate[0];
        }
    }

    // Chỉ chủ sở hữu được đọc/ghi tệp (quyền rw-------).
    private static void restrictToOwner(Path file) {
        // Best effort: chỉ chủ sở hữu được đọc tệp khoá (POSIX); Windows bỏ qua.
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Hệ thống không hỗ trợ quyền POSIX (Windows): bỏ qua.
        }
    }
}
