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
import com.mailsystem.server.security.Totp;
import com.mailsystem.server.session.ResumeTokens;
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
            ResumeTokens.get().revokeAll(accountId); // token cũ (có thể đã bị lộ) hết hiệu lực
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

    // ------------------------------------------------------------------ cài đặt & xác thực 2 lớp

    public ResponseMessage getSettings(String requestId, Integer accountId) {
        try {
            return accountDAO.findSecuritySettings(accountId)
                    .map(settings -> ResponseMessage.ok(requestId, com.mailsystem.common.protocol.ProtocolUtil.getGson()
                            .toJsonTree(new com.mailsystem.common.model.AccountSettings(settings.shareReadReceipts(),
                                    settings.totpEnabled()))))
                    .orElseGet(() -> ResponseMessage.error(requestId, "Không tìm thấy tài khoản."));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không tải được cài đặt.");
        }
    }

    /** {shareReadReceipts: bool}. */
    public ResponseMessage updateSettings(String requestId, Integer accountId, JsonElement payload) {
        if (payload == null || !payload.isJsonObject() || !payload.getAsJsonObject().has("shareReadReceipts")) {
            return ResponseMessage.error(requestId, "Cài đặt không hợp lệ.");
        }
        try {
            accountDAO.updateShareReadReceipts(accountId, payload.getAsJsonObject().get("shareReadReceipts").getAsBoolean());
            return getSettings(requestId, accountId);
        } catch (SQLException | RuntimeException error) {
            return ResponseMessage.error(requestId, "Không lưu được cài đặt.");
        }
    }

    /**
     * Bước 1 bật 2FA: sinh khoá bí mật mới (chưa bật). Client hiện mã QR từ
     * otpauthUri để người dùng quét bằng ứng dụng xác thực.
     */
    public ResponseMessage totpSetup(String requestId, ConnectionContext context) {
        int accountId = context.accountId();
        try {
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            if (settings.isPresent() && settings.get().totpEnabled()) {
                return ResponseMessage.error(requestId, "Xác thực 2 lớp đang bật. Tắt trước nếu muốn đổi khoá.");
            }
            Optional<Account> account = accountDAO.findById(accountId);
            String secret = Totp.newSecret();
            accountDAO.updateTotp(accountId, secret, false);
            JsonObject data = new JsonObject();
            data.addProperty("secret", secret);
            data.addProperty("otpauthUri", Totp.otpauthUri(account.map(Account::getEmail).orElse(""), secret));
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không tạo được khoá xác thực.");
        }
    }

    /** Bước 2 bật 2FA: nhập đúng một mã từ ứng dụng để chứng minh đã lưu khoá. */
    public ResponseMessage totpEnable(String requestId, ConnectionContext context, JsonElement payload) {
        int accountId = context.accountId();
        String code = payload != null && payload.isJsonObject() ? AuthService.getString(payload.getAsJsonObject(), "code").trim() : "";
        try {
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            if (settings.isEmpty() || settings.get().totpSecret() == null) {
                return ResponseMessage.error(requestId, "Hãy tạo khoá xác thực trước.");
            }
            if (settings.get().totpEnabled()) {
                return ResponseMessage.error(requestId, "Xác thực 2 lớp đã được bật.");
            }
            long step = Totp.verify(settings.get().totpSecret(), code, System.currentTimeMillis() / 1000);
            if (step < 0) {
                return ResponseMessage.error(requestId, "Mã không đúng. Kiểm tra giờ trên điện thoại và nhập mã mới.");
            }
            accountDAO.updateTotp(accountId, settings.get().totpSecret(), true);
            accountDAO.claimTotpStep(accountId, step);
            SecurityLog.event("TOTP_ENABLED", context.remoteIp(), "accountId=" + accountId);
            return getSettings(requestId, accountId);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không bật được xác thực 2 lớp.");
        }
    }

    /** Tắt 2FA: cần cả mật khẩu và một mã hợp lệ (lấy được máy đang đăng nhập cũng không tắt được). */
    public ResponseMessage totpDisable(String requestId, ConnectionContext context, JsonElement payload) {
        int accountId = context.accountId();
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thông tin không hợp lệ.");
        }
        String password = AuthService.getString(payload.getAsJsonObject(), "password");
        String code = AuthService.getString(payload.getAsJsonObject(), "code").trim();
        try {
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            Optional<String> hash = accountDAO.findPasswordHashById(accountId);
            if (settings.isEmpty() || !settings.get().totpEnabled() || hash.isEmpty()) {
                return ResponseMessage.error(requestId, "Xác thực 2 lớp chưa được bật.");
            }
            if (!PasswordHasher.verify(password, hash.get())) {
                SecurityLog.event("TOTP_DISABLE_FAILED", context.remoteIp(), "accountId=" + accountId);
                return ResponseMessage.error(requestId, "Mật khẩu không đúng.");
            }
            long step = Totp.verify(settings.get().totpSecret(), code, System.currentTimeMillis() / 1000);
            if (step < 0 || !accountDAO.claimTotpStep(accountId, step)) {
                return ResponseMessage.error(requestId, "Mã xác thực không đúng hoặc đã được dùng.");
            }
            accountDAO.updateTotp(accountId, null, false);
            SecurityLog.event("TOTP_DISABLED", context.remoteIp(), "accountId=" + accountId);
            return getSettings(requestId, accountId);
        } catch (SQLException | GeneralSecurityException error) {
            return ResponseMessage.error(requestId, "Không tắt được xác thực 2 lớp.");
        }
    }

    /** Thu hồi mọi phiên khác rồi đăng xuất phiên hiện tại. */
    public ResponseMessage logoutAll(String requestId, ConnectionContext context) {
        int accountId = context.accountId();
        ResumeTokens.get().revokeAll(accountId);
        int revoked = SessionManager.getInstance().revokeAll(accountId, context.session(),
                "Tài khoản đã đăng xuất khỏi mọi thiết bị.");
        context.signOut();
        SecurityLog.event("LOGOUT_ALL", context.remoteIp(), "accountId=" + accountId + " revokedSessions=" + revoked);
        JsonObject data = new JsonObject();
        data.addProperty("revokedSessions", revoked);
        return ResponseMessage.ok(requestId, data);
    }
}
