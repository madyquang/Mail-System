package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
import com.mailsystem.server.net.ConnectionContext;
import com.mailsystem.server.security.LoginThrottle;
import com.mailsystem.server.security.PasswordHasher;
import com.mailsystem.server.security.PasswordPolicy;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.session.SessionManager;

import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/** Quản lý tài khoản: đổi tên hiển thị, đổi mật khẩu, đăng xuất khỏi mọi thiết bị. */
public class AccountService {

    private final AccountDAO accountDAO;
    private final LoginThrottle loginThrottle;

    public AccountService(LoginThrottle loginThrottle) {
        this(new JdbcAccountDAO(), loginThrottle);
    }

    AccountService(AccountDAO accountDAO, LoginThrottle loginThrottle) {
        this.accountDAO = accountDAO;
        this.loginThrottle = loginThrottle;
    }

    public ResponseMessage updateProfile(String requestId, Integer accountId, JsonElement payload) {
        String displayName = payload != null && payload.isJsonObject()
                ? AuthService.getString(payload.getAsJsonObject(), "displayName").trim()
                : "";
        if (displayName.isBlank() || displayName.length() > ProtocolConstants.MAX_DISPLAY_NAME_CHARS
                || AuthService.containsControl(displayName)) {
            return ResponseMessage.error(requestId, "Tên hiển thị phải từ 1 đến 100 ký tự.");
        }
        try {
            if (!accountDAO.updateDisplayName(accountId, displayName)) {
                return ResponseMessage.error(requestId, "Không tìm thấy tài khoản.");
            }
            JsonObject data = new JsonObject();
            data.addProperty("displayName", displayName);
            SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.PROFILE_UPDATED, data));
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể cập nhật hồ sơ.");
        }
    }

    /**
     * Payload {currentPassword, newPassword}. Thành công thì mọi phiên KHÁC của
     * tài khoản bị thu hồi; phiên hiện tại vẫn tiếp tục.
     */
    public ResponseMessage changePassword(String requestId, ConnectionContext context, JsonElement payload) {
        Integer accountId = context.accountId();
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thông tin đổi mật khẩu không hợp lệ.");
        }
        String current = AuthService.getString(payload.getAsJsonObject(), "currentPassword");
        String next = AuthService.getString(payload.getAsJsonObject(), "newPassword");
        try {
            Optional<Account> account = accountDAO.findById(accountId);
            Optional<String> storedHash = accountDAO.findPasswordHashById(accountId);
            if (account.isEmpty() || storedHash.isEmpty()) {
                return ResponseMessage.error(requestId, "Không tìm thấy tài khoản.");
            }
            String email = account.get().getEmail();
            // Dùng chung bộ đếm với LOGIN: không thể dò mật khẩu hiện tại qua chức năng này.
            Duration locked = loginThrottle.lockRemaining(email, context.remoteIp());
            if (!locked.isZero()) {
                return ResponseMessage.error(requestId, AuthService.lockMessage(locked));
            }
            if (current.length() > ProtocolConstants.MAX_PASSWORD_LENGTH
                    || !PasswordHasher.verify(current, storedHash.get())) {
                loginThrottle.recordFailure(email, context.remoteIp());
                SecurityLog.event("CHANGE_PASSWORD_FAILED", context.remoteIp(), "accountId=" + accountId);
                return ResponseMessage.error(requestId, "Mật khẩu hiện tại không đúng.");
            }
            String policyError = PasswordPolicy.validate(next, email);
            if (policyError != null) {
                return ResponseMessage.error(requestId, policyError);
            }
            if (next.equals(current)) {
                return ResponseMessage.error(requestId, "Mật khẩu mới phải khác mật khẩu hiện tại.");
            }
            accountDAO.updatePasswordHash(accountId, PasswordHasher.hash(next));
            int revoked = SessionManager.getInstance().revokeAll(accountId, context.session(),
                    "Mật khẩu tài khoản vừa được đổi từ một thiết bị khác. Vui lòng đăng nhập lại.");
            SecurityLog.event("CHANGE_PASSWORD", context.remoteIp(),
                    "accountId=" + accountId + " revokedSessions=" + revoked);
            JsonObject data = new JsonObject();
            data.addProperty("revokedSessions", revoked);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể đổi mật khẩu lúc này.");
        } catch (GeneralSecurityException error) {
            return ResponseMessage.error(requestId, "Không thể đổi mật khẩu lúc này.");
        }
    }

    /** Thu hồi mọi phiên khác rồi đăng xuất phiên hiện tại. */
    public ResponseMessage logoutAll(String requestId, ConnectionContext context) {
        int accountId = context.accountId();
        int revoked = SessionManager.getInstance().revokeAll(accountId, context.session(),
                "Tài khoản đã đăng xuất khỏi mọi thiết bị.");
        context.signOut();
        SecurityLog.event("LOGOUT_ALL", context.remoteIp(), "accountId=" + accountId + " revokedSessions=" + revoked);
        JsonObject data = new JsonObject();
        data.addProperty("revokedSessions", revoked);
        return ResponseMessage.ok(requestId, data);
    }
}
