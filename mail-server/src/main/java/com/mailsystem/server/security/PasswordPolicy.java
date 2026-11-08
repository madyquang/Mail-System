// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// ProtocolConstants: giới hạn độ dài mật khẩu dùng chung với Client.
import com.mailsystem.common.protocol.ProtocolConstants;

// Locale.ROOT: đổi chữ thường không phụ thuộc ngôn ngữ máy.
import java.util.Locale;
// Set: tập hợp không trùng phần tử.
import java.util.Set;

/** Chính sách mật khẩu cho đăng ký và đổi mật khẩu. */
public final class PasswordPolicy {

    /** Một số mật khẩu phổ biến nhất (đủ để chặn các lựa chọn tệ nhất). */
    private static final Set<String> COMMON = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1", "password123", "qwerty123",
            "abc12345", "11111111", "00000000", "iloveyou1", "matkhau123", "admin123", "letmein1");

    // Constructor private: lớp chỉ có hàm tĩnh.
    private PasswordPolicy() {
    }

    /** @return null nếu hợp lệ, ngược lại là thông báo lỗi tiếng Việt */
    public static String validate(String password, String email) {
        // Quá ngắn (hoặc null).
        if (password == null || password.length() < ProtocolConstants.MIN_PASSWORD_LENGTH) {
            return "Mật khẩu cần ít nhất " + ProtocolConstants.MIN_PASSWORD_LENGTH + " ký tự.";
        }
        // Quá dài (chặn tốn CPU khi băm).
        if (password.length() > ProtocolConstants.MAX_PASSWORD_LENGTH) {
            return "Mật khẩu không được dài quá " + ProtocolConstants.MAX_PASSWORD_LENGTH + " ký tự.";
        }
        // Có ít nhất một chữ cái?
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        // Có ít nhất một chữ số?
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        // Thiếu một trong hai thì không đạt.
        if (!hasLetter || !hasDigit) {
            return "Mật khẩu cần có cả chữ cái và chữ số.";
        }
        // Bản chữ thường để so sánh không phân biệt hoa thường.
        String lower = password.toLowerCase(Locale.ROOT);
        // Nằm trong danh sách mật khẩu phổ biến.
        if (COMMON.contains(lower)) {
            return "Mật khẩu này quá phổ biến, hãy chọn mật khẩu khác.";
        }
        // Mật khẩu chứa phần tên của email (vd email "an123@..." và mật khẩu "an123abc").
        if (email != null && email.contains("@")) {
            // Phần trước @ của email, chữ thường.
            String localPart = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            // Chỉ xét khi phần tên đủ dài (>= 3 ký tự) để tránh chặn nhầm.
            if (localPart.length() >= 3 && lower.contains(localPart)) {
                return "Mật khẩu không được chứa tên email.";
            }
        }
        // Qua mọi kiểm tra: hợp lệ.
        return null;
    }
}
