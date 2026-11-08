// Gói "app": trạng thái và điều hướng chung của ứng dụng Client.
package com.mailsystem.client.app;

// DTO tài khoản, cổng mặc định; thuộc tính JavaFX.
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.ProtocolConstants;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

// Preferences: lưu cài đặt nhỏ của người dùng (Windows: trong Registry).
import java.util.prefs.Preferences;

/**
 * Trạng thái phiên phía Client: tài khoản đang đăng nhập và máy chủ đang dùng.
 * currentAccount là property để giao diện tự cập nhật khi đổi tên hiển thị.
 * Địa chỉ máy chủ và email lần trước được nhớ bằng java.util.prefs (KHÔNG nhớ mật khẩu).
 */
public final class Session {

    // Nơi lưu cài đặt; tài khoản đang đăng nhập (property để giao diện tự cập nhật).
    private static final Preferences PREFS = Preferences.userNodeForPackage(Session.class);
    private static final ObjectProperty<Account> ACCOUNT = new SimpleObjectProperty<>();

    // Máy chủ đang dùng (đọc từ biến môi trường hoặc lần dùng trước).
    private static String serverHost = initialHost();
    private static int serverPort = initialPort();
    /** Token tiếp tục phiên: chỉ giữ trong RAM, mất khi thoát ứng dụng (không ghi ra đĩa). */
    private static volatile String resumeToken;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private Session() {
    }

    /** Thuộc tính tài khoản (giao diện ràng buộc vào để tự cập nhật). */
    public static ObjectProperty<Account> accountProperty() {
        return ACCOUNT;
    }

    /** Tài khoản đang đăng nhập, hoặc null. */
    public static Account account() {
        return ACCOUNT.get();
    }

    /** Ghi nhận đăng nhập và nhớ email cho lần sau. */
    public static void signIn(Account account) {
        ACCOUNT.set(account);
        PREFS.put("lastEmail", account.getEmail() == null ? "" : account.getEmail());
    }

    /** Đăng xuất: xoá tài khoản và token. */
    public static void signOut() {
        ACCOUNT.set(null);
        resumeToken = null;
    }

    /** Token tiếp tục phiên hiện tại. */
    public static String resumeToken() {
        return resumeToken;
    }

    /** Lưu token mới (Server cấp mới sau mỗi lần dùng). */
    public static void setResumeToken(String token) {
        resumeToken = token;
    }

    /** Giao diện tối (lưu trên máy này). */
    public static boolean darkMode() {
        return PREFS.getBoolean("darkMode", false);
    }

    /** Bật / tắt giao diện tối. */
    public static void setDarkMode(boolean dark) {
        PREFS.putBoolean("darkMode", dark);
    }

    /** Gộp thư theo hội thoại trong danh sách thư. */
    public static boolean threadedView() {
        return PREFS.getBoolean("threadedView", true);
    }

    /** Bật / tắt gộp theo hội thoại. */
    public static void setThreadedView(boolean threaded) {
        PREFS.putBoolean("threadedView", threaded);
    }

    /** Cập nhật tên hiển thị: tạo Account mới để property báo thay đổi cho giao diện. */
    public static void updateDisplayName(String displayName) {
        Account current = ACCOUNT.get();
        if (current != null) {
            ACCOUNT.set(new Account(current.getAccountId(), current.getEmail(), displayName));
        }
    }

    /** Tối đa 8 từ khoá tìm gần nhất (mới nhất trước), lưu trên máy này. */
    public static java.util.List<String> recentSearches() {
        // Các từ khoá lưu thành một chuỗi, cách nhau ký tự xuống dòng.
        String raw = PREFS.get("recentSearches", "");
        return raw.isEmpty() ? java.util.List.of() : java.util.List.of(raw.split("\n"));
    }

    /** Thêm từ khoá vào đầu danh sách tìm gần đây (bỏ trùng, tối đa 8). */
    public static void addRecentSearch(String query) {
        // Bỏ khoảng trắng, thay xuống dòng bằng dấu cách; rỗng hoặc quá dài thì bỏ qua.
        String value = query == null ? "" : query.trim().replace('\n', ' ');
        if (value.isEmpty() || value.length() > 100) {
            return;
        }
        // Từ khoá mới ở đầu, rồi các từ cũ khác nó (không phân biệt hoa thường), tối đa 8.
        java.util.List<String> updated = new java.util.ArrayList<>();
        updated.add(value);
        for (String old : recentSearches()) {
            if (!old.equalsIgnoreCase(value) && updated.size() < 8) {
                updated.add(old);
            }
        }
        // Lưu lại.
        PREFS.put("recentSearches", String.join("\n", updated));
    }

    /** Xoá lịch sử tìm kiếm. */
    public static void clearRecentSearches() {
        PREFS.remove("recentSearches");
    }

    /** Email đăng nhập lần trước (điền sẵn ở màn đăng nhập). */
    public static String lastEmail() {
        return PREFS.get("lastEmail", "");
    }

    /** Tên / IP máy chủ. */
    public static String serverHost() {
        return serverHost;
    }

    /** Cổng máy chủ. */
    public static int serverPort() {
        return serverPort;
    }

    /** "host:port". */
    public static String serverAddress() {
        return serverHost + ":" + serverPort;
    }

    /**
     * Đặt địa chỉ máy chủ dạng "host" hoặc "host:port".
     *
     * @return null nếu hợp lệ, ngược lại là thông báo lỗi
     */
    public static String setServerAddress(String address) {
        // Bỏ khoảng trắng; rỗng thì báo lỗi.
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) {
            return "Nhập địa chỉ máy chủ, VD: localhost:5000.";
        }
        // Mặc định cổng 5000; có đúng một dấu ':' thì tách host và cổng.
        String host = value;
        int port = ProtocolConstants.DEFAULT_PORT;
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') == colon) {
            host = value.substring(0, colon).trim();
            port = parsePort(value.substring(colon + 1).trim(), -1);
            if (port < 0) {
                return "Cổng máy chủ phải là số từ 1 đến 65535.";
            }
        }
        // Host chỉ gồm chữ, số, '.', '-'.
        if (host.isEmpty() || !host.matches("[A-Za-z0-9.\\-]+")) {
            return "Địa chỉ máy chủ không hợp lệ.";
        }
        // Hợp lệ: lưu lại cho lần sau.
        serverHost = host;
        serverPort = port;
        PREFS.put("serverAddress", serverAddress());
        return null;
    }

    // Host ban đầu: biến môi trường MAIL_SERVER_HOST, rồi địa chỉ đã lưu, mặc định localhost.
    private static String initialHost() {
        String env = System.getenv("MAIL_SERVER_HOST");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String saved = PREFS.get("serverAddress", "");
        return saved.contains(":") ? saved.substring(0, saved.lastIndexOf(':')) : "localhost";
    }

    // Cổng ban đầu: biến môi trường MAIL_SERVER_PORT, rồi địa chỉ đã lưu, mặc định 5000.
    private static int initialPort() {
        String env = System.getenv("MAIL_SERVER_PORT");
        if (env != null && !env.isBlank()) {
            return parsePort(env.trim(), ProtocolConstants.DEFAULT_PORT);
        }
        String saved = PREFS.get("serverAddress", "");
        return saved.contains(":")
                ? parsePort(saved.substring(saved.lastIndexOf(':') + 1), ProtocolConstants.DEFAULT_PORT)
                : ProtocolConstants.DEFAULT_PORT;
    }

    // Đọc cổng 1..65535; sai thì trả fallback.
    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
