package com.mailsystem.server.security;

import com.mailsystem.common.protocol.ProtocolConstants;

import java.util.Locale;
import java.util.Set;

/** Chính sách mật khẩu cho đăng ký và đổi mật khẩu. */
public final class PasswordPolicy {

    /** Một số mật khẩu phổ biến nhất (đủ để chặn các lựa chọn tệ nhất). */
    private static final Set<String> COMMON = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1", "password123", "qwerty123",
            "abc12345", "11111111", "00000000", "iloveyou1", "matkhau123", "admin123", "letmein1");

    private PasswordPolicy() {
    }

    /** @return null nếu hợp lệ, ngược lại là thông báo lỗi tiếng Việt */
    public static String validate(String password, String email) {
        if (password == null || password.length() < ProtocolConstants.MIN_PASSWORD_LENGTH) {
            return "Mật khẩu cần ít nhất " + ProtocolConstants.MIN_PASSWORD_LENGTH + " ký tự.";
        }
        if (password.length() > ProtocolConstants.MAX_PASSWORD_LENGTH) {
            return "Mật khẩu không được dài quá " + ProtocolConstants.MAX_PASSWORD_LENGTH + " ký tự.";
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "Mật khẩu cần có cả chữ cái và chữ số.";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            return "Mật khẩu này quá phổ biến, hãy chọn mật khẩu khác.";
        }
        if (email != null && email.contains("@")) {
            String localPart = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (localPart.length() >= 3 && lower.contains(localPart)) {
                return "Mật khẩu không được chứa tên email.";
            }
        }
        return null;
    }
}
