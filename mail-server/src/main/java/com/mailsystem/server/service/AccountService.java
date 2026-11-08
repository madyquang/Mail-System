// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// JSON, DTO tài khoản, EVENT, hằng số, câu trả lời.
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ResponseMessage;
// DAO tài khoản.
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
// Trạng thái kết nối.
import com.mailsystem.server.net.ConnectionContext;
// Bảo mật: khoá đăng nhập sai, băm mật khẩu, chính sách mật khẩu, nhật ký, TOTP.
import com.mailsystem.server.security.LoginThrottle;
import com.mailsystem.server.security.PasswordHasher;
import com.mailsystem.server.security.PasswordPolicy;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.security.Totp;
// Token tiếp tục phiên và quản lý phiên.
import com.mailsystem.server.session.ResumeTokens;
import com.mailsystem.server.session.SessionManager;

// Lỗi mật mã, lỗi CSDL, thời gian, Optional.
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/** Quản lý tài khoản: đổi tên hiển thị, đổi mật khẩu, đăng xuất khỏi mọi thiết bị. */
public class AccountService {

    // Truy cập bảng account.
    private final AccountDAO accountDAO;
    // Dùng chung với AuthService để đếm lần nhập sai mật khẩu.
    private final LoginThrottle loginThrottle;

    /** Constructor dùng khi chạy thật. */
    public AccountService(LoginThrottle loginThrottle) {
        this(new JdbcAccountDAO(), loginThrottle);
    }

    // Constructor nhận DAO từ ngoài (unit test).
    AccountService(AccountDAO accountDAO, LoginThrottle loginThrottle) {
        this.accountDAO = accountDAO;
        this.loginThrottle = loginThrottle;
    }

    /** UPDATE_PROFILE {displayName} → {displayName}. */
    public ResponseMessage updateProfile(String requestId, Integer accountId, JsonElement payload) {
        // Đọc tên mới (thiếu payload thì chuỗi rỗng).
        String displayName = payload != null && payload.isJsonObject()
                ? AuthService.getString(payload.getAsJsonObject(), "displayName").trim()
                : "";
        // Rỗng, quá dài hoặc chứa ký tự điều khiển: không hợp lệ.
        if (displayName.isBlank() || displayName.length() > ProtocolConstants.MAX_DISPLAY_NAME_CHARS
                || AuthService.containsControl(displayName)) {
            return ResponseMessage.error(requestId, "Tên hiển thị phải từ 1 đến 100 ký tự.");
        }
        try {
            // Cập nhật CSDL.
            if (!accountDAO.updateDisplayName(accountId, displayName)) {
                return ResponseMessage.error(requestId, "Không tìm thấy tài khoản.");
            }
            // Báo các máy đang mở của tài khoản cập nhật tên.
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
        // Mật khẩu hiện tại và mật khẩu mới.
        String current = AuthService.getString(payload.getAsJsonObject(), "currentPassword");
        String next = AuthService.getString(payload.getAsJsonObject(), "newPassword");
        try {
            // Đọc tài khoản và chuỗi băm mật khẩu đang lưu.
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
            // Mật khẩu hiện tại quá dài hoặc sai: đếm một lần sai, ghi nhật ký.
            if (current.length() > ProtocolConstants.MAX_PASSWORD_LENGTH
                    || !PasswordHasher.verify(current, storedHash.get())) {
                loginThrottle.recordFailure(email, context.remoteIp());
                SecurityLog.event("CHANGE_PASSWORD_FAILED", context.remoteIp(), "accountId=" + accountId);
                return ResponseMessage.error(requestId, "Mật khẩu hiện tại không đúng.");
            }
            // Mật khẩu mới phải đạt chính sách.
            String policyError = PasswordPolicy.validate(next, email);
            if (policyError != null) {
                return ResponseMessage.error(requestId, policyError);
            }
            // Không được trùng mật khẩu cũ.
            if (next.equals(current)) {
                return ResponseMessage.error(requestId, "Mật khẩu mới phải khác mật khẩu hiện tại.");
            }
            // Lưu chuỗi băm của mật khẩu mới.
            accountDAO.updatePasswordHash(accountId, PasswordHasher.hash(next));
            ResumeTokens.get().revokeAll(accountId); // token cũ (có thể đã bị lộ) hết hiệu lực
            // Đá mọi phiên khác ra, trừ phiên đang đổi mật khẩu.
            int revoked = SessionManager.getInstance().revokeAll(accountId, context.session(),
                    "Mật khẩu tài khoản vừa được đổi từ một thiết bị khác. Vui lòng đăng nhập lại.");
            SecurityLog.event("CHANGE_PASSWORD", context.remoteIp(),
                    "accountId=" + accountId + " revokedSessions=" + revoked);
            // Trả số phiên đã thu hồi (CommandRouter sẽ thêm token mới cho phiên này).
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

    /** GET_SETTINGS → AccountSettings {shareReadReceipts, totpEnabled}. */
    public ResponseMessage getSettings(String requestId, Integer accountId) {
        try {
            // Có cài đặt thì đổi thành AccountSettings rồi JSON; không thì báo lỗi.
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
        // Phải có trường shareReadReceipts.
        if (payload == null || !payload.isJsonObject() || !payload.getAsJsonObject().has("shareReadReceipts")) {
            return ResponseMessage.error(requestId, "Cài đặt không hợp lệ.");
        }
        try {
            // Lưu rồi trả lại cài đặt mới nhất.
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
            // Đang bật thì không cho tạo khoá mới (phải tắt trước).
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            if (settings.isPresent() && settings.get().totpEnabled()) {
                return ResponseMessage.error(requestId, "Xác thực 2 lớp đang bật. Tắt trước nếu muốn đổi khoá.");
            }
            Optional<Account> account = accountDAO.findById(accountId);
            // Sinh khoá mới và lưu ở trạng thái chưa bật.
            String secret = Totp.newSecret();
            accountDAO.updateTotp(accountId, secret, false);
            // Trả khoá (để nhập tay) và URI (để vẽ mã QR).
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
        // Mã 6 số người dùng nhập.
        String code = payload != null && payload.isJsonObject() ? AuthService.getString(payload.getAsJsonObject(), "code").trim() : "";
        try {
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            // Chưa có khoá (chưa qua bước 1).
            if (settings.isEmpty() || settings.get().totpSecret() == null) {
                return ResponseMessage.error(requestId, "Hãy tạo khoá xác thực trước.");
            }
            if (settings.get().totpEnabled()) {
                return ResponseMessage.error(requestId, "Xác thực 2 lớp đã được bật.");
            }
            // Kiểm tra mã theo giờ hiện tại (giây kể từ 1/1/1970).
            long step = Totp.verify(settings.get().totpSecret(), code, System.currentTimeMillis() / 1000);
            if (step < 0) {
                return ResponseMessage.error(requestId, "Mã không đúng. Kiểm tra giờ trên điện thoại và nhập mã mới.");
            }
            // Bật 2FA và đánh dấu mã vừa dùng (không dùng lại được để đăng nhập).
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
            // Chưa bật thì không có gì để tắt.
            if (settings.isEmpty() || !settings.get().totpEnabled() || hash.isEmpty()) {
                return ResponseMessage.error(requestId, "Xác thực 2 lớp chưa được bật.");
            }
            // Kiểm tra mật khẩu.
            if (!PasswordHasher.verify(password, hash.get())) {
                SecurityLog.event("TOTP_DISABLE_FAILED", context.remoteIp(), "accountId=" + accountId);
                return ResponseMessage.error(requestId, "Mật khẩu không đúng.");
            }
            // Kiểm tra mã và đánh dấu đã dùng.
            long step = Totp.verify(settings.get().totpSecret(), code, System.currentTimeMillis() / 1000);
            if (step < 0 || !accountDAO.claimTotpStep(accountId, step)) {
                return ResponseMessage.error(requestId, "Mã xác thực không đúng hoặc đã được dùng.");
            }
            // Xoá khoá và tắt 2FA.
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
        // Huỷ mọi token tiếp tục phiên.
        ResumeTokens.get().revokeAll(accountId);
        // Đá mọi phiên khác.
        int revoked = SessionManager.getInstance().revokeAll(accountId, context.session(),
                "Tài khoản đã đăng xuất khỏi mọi thiết bị.");
        // Đăng xuất chính phiên này.
        context.signOut();
        SecurityLog.event("LOGOUT_ALL", context.remoteIp(), "accountId=" + accountId + " revokedSessions=" + revoked);
        JsonObject data = new JsonObject();
        data.addProperty("revokedSessions", revoked);
        return ResponseMessage.ok(requestId, data);
    }
}
