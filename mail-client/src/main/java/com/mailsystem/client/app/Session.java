package com.mailsystem.client.app;

import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.ProtocolConstants;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.prefs.Preferences;

/**
 * Trạng thái phiên phía Client: tài khoản đang đăng nhập và máy chủ đang dùng.
 * currentAccount là property để giao diện tự cập nhật khi đổi tên hiển thị.
 * Địa chỉ máy chủ và email lần trước được nhớ bằng java.util.prefs (KHÔNG nhớ mật khẩu).
 */
public final class Session {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Session.class);
    private static final ObjectProperty<Account> ACCOUNT = new SimpleObjectProperty<>();

    private static String serverHost = initialHost();
    private static int serverPort = initialPort();
    /** Token tiếp tục phiên: chỉ giữ trong RAM, mất khi thoát ứng dụng (không ghi ra đĩa). */
    private static volatile String resumeToken;

    private Session() {
    }

    public static ObjectProperty<Account> accountProperty() {
        return ACCOUNT;
    }

    public static Account account() {
        return ACCOUNT.get();
    }

    public static void signIn(Account account) {
        ACCOUNT.set(account);
        PREFS.put("lastEmail", account.getEmail() == null ? "" : account.getEmail());
    }

    public static void signOut() {
        ACCOUNT.set(null);
        resumeToken = null;
    }

    public static String resumeToken() {
        return resumeToken;
    }

    public static void setResumeToken(String token) {
        resumeToken = token;
    }

    /** Giao diện tối (lưu trên máy này). */
    public static boolean darkMode() {
        return PREFS.getBoolean("darkMode", false);
    }

    public static void setDarkMode(boolean dark) {
        PREFS.putBoolean("darkMode", dark);
    }

    /** Gộp thư theo hội thoại trong danh sách thư. */
    public static boolean threadedView() {
        return PREFS.getBoolean("threadedView", true);
    }

    public static void setThreadedView(boolean threaded) {
        PREFS.putBoolean("threadedView", threaded);
    }

    public static void updateDisplayName(String displayName) {
        Account current = ACCOUNT.get();
        if (current != null) {
            ACCOUNT.set(new Account(current.getAccountId(), current.getEmail(), displayName));
        }
    }

    /** Tối đa 8 từ khoá tìm gần nhất (mới nhất trước), lưu trên máy này. */
    public static java.util.List<String> recentSearches() {
        String raw = PREFS.get("recentSearches", "");
        return raw.isEmpty() ? java.util.List.of() : java.util.List.of(raw.split("\n"));
    }

    public static void addRecentSearch(String query) {
        String value = query == null ? "" : query.trim().replace('\n', ' ');
        if (value.isEmpty() || value.length() > 100) {
            return;
        }
        java.util.List<String> updated = new java.util.ArrayList<>();
        updated.add(value);
        for (String old : recentSearches()) {
            if (!old.equalsIgnoreCase(value) && updated.size() < 8) {
                updated.add(old);
            }
        }
        PREFS.put("recentSearches", String.join("\n", updated));
    }

    public static void clearRecentSearches() {
        PREFS.remove("recentSearches");
    }

    public static String lastEmail() {
        return PREFS.get("lastEmail", "");
    }

    public static String serverHost() {
        return serverHost;
    }

    public static int serverPort() {
        return serverPort;
    }

    public static String serverAddress() {
        return serverHost + ":" + serverPort;
    }

    /**
     * Đặt địa chỉ máy chủ dạng "host" hoặc "host:port".
     *
     * @return null nếu hợp lệ, ngược lại là thông báo lỗi
     */
    public static String setServerAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) {
            return "Nhập địa chỉ máy chủ, VD: localhost:5000.";
        }
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
        if (host.isEmpty() || !host.matches("[A-Za-z0-9.\\-]+")) {
            return "Địa chỉ máy chủ không hợp lệ.";
        }
        serverHost = host;
        serverPort = port;
        PREFS.put("serverAddress", serverAddress());
        return null;
    }

    private static String initialHost() {
        String env = System.getenv("MAIL_SERVER_HOST");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String saved = PREFS.get("serverAddress", "");
        return saved.contains(":") ? saved.substring(0, saved.lastIndexOf(':')) : "localhost";
    }

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

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
