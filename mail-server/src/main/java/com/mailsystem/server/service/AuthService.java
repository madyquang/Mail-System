package com.mailsystem.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.protocol.ErrorCodes;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;
import com.mailsystem.server.db.DatabaseConnection;
import com.mailsystem.server.security.LoginThrottle;
import com.mailsystem.server.security.PasswordHasher;
import com.mailsystem.server.security.PasswordPolicy;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.security.SlidingWindowLimiter;
import com.mailsystem.server.security.Totp;
import com.mailsystem.server.session.ResumeTokens;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Đăng ký / đăng nhập. Mật khẩu băm PBKDF2 (PasswordHasher); chống dò mật khẩu
 * bằng LoginThrottle; giới hạn số lần đăng ký mỗi IP; không bao giờ cho biết
 * email nào đã tồn tại qua thông báo lỗi hay thời gian phản hồi.
 */
public class AuthService {

    static final String INVALID_CREDENTIALS = "Email hoặc mật khẩu không đúng";
    private static final String EMAIL_PATTERN = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

    private final AccountDAO accountDAO;
    private final FolderDAO folderDAO;
    private final LoginThrottle loginThrottle;
    private final SlidingWindowLimiter registrationLimiter;

    public AuthService(LoginThrottle loginThrottle, SlidingWindowLimiter registrationLimiter) {
        this(new JdbcAccountDAO(), new JdbcFolderDAO(), loginThrottle, registrationLimiter);
    }

    AuthService(AccountDAO accountDAO, FolderDAO folderDAO, LoginThrottle loginThrottle,
            SlidingWindowLimiter registrationLimiter) {
        this.accountDAO = accountDAO;
        this.folderDAO = folderDAO;
        this.loginThrottle = loginThrottle;
        this.registrationLimiter = registrationLimiter;
    }

    public ResponseMessage register(String requestId, JsonElement payload, String ip) {
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thông tin đăng ký không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        String email = getString(values, "email").trim().toLowerCase(Locale.ROOT);
        String displayName = getString(values, "displayName").trim();
        String password = getString(values, "password");

        if (email.isBlank() || displayName.isBlank() || password.isBlank()) {
            return ResponseMessage.error(requestId, "Vui lòng điền đầy đủ thông tin đăng ký.");
        }
        if (email.length() > 150 || !email.matches(EMAIL_PATTERN)) {
            return ResponseMessage.error(requestId, "Địa chỉ email chưa đúng định dạng.");
        }
        if (displayName.length() > ProtocolConstants.MAX_DISPLAY_NAME_CHARS || containsControl(displayName)) {
            return ResponseMessage.error(requestId, "Tên hiển thị tối đa 100 ký tự và không chứa ký tự điều khiển.");
        }
        String policyError = PasswordPolicy.validate(password, email);
        if (policyError != null) {
            return ResponseMessage.error(requestId, policyError);
        }
        if (!registrationLimiter.tryAcquire(ip)) {
            SecurityLog.event("REGISTER_RATE_LIMITED", ip, "email=" + email);
            return ResponseMessage.error(requestId, "Có quá nhiều lượt đăng ký từ máy này, vui lòng thử lại sau.");
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (accountDAO.existsByEmail(connection, email)) {
                    connection.rollback();
                    return ResponseMessage.error(requestId, "Email này đã được đăng ký.");
                }
                int accountId = accountDAO.insertAccount(connection, email, PasswordHasher.hash(password), displayName);
                folderDAO.createDefaultFolders(connection, accountId);
                connection.commit();
                SecurityLog.event("REGISTER", ip, "accountId=" + accountId);

                JsonObject data = new JsonObject();
                data.addProperty("accountId", accountId);
                data.addProperty("email", email);
                data.addProperty("displayName", displayName);
                return ResponseMessage.ok(requestId, data);
            } catch (SQLException | GeneralSecurityException error) {
                connection.rollback();
                if (error instanceof SQLException sqlError && isConstraintViolation(sqlError)) {
                    return ResponseMessage.error(requestId, "Email này đã được đăng ký.");
                }
                return ResponseMessage.error(requestId, "Không thể tạo tài khoản lúc này.");
            }
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể kết nối cơ sở dữ liệu.");
        }
    }

    public ResponseMessage login(String requestId, JsonElement payload, String ip) {
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
        }
        JsonObject values = payload.getAsJsonObject();
        String email = getString(values, "email").trim().toLowerCase(Locale.ROOT);
        String password = getString(values, "password");
        if (email.isBlank() || password.isBlank() || password.length() > ProtocolConstants.MAX_PASSWORD_LENGTH) {
            return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
        }

        Duration locked = loginThrottle.lockRemaining(email, ip);
        if (!locked.isZero()) {
            SecurityLog.event("LOGIN_BLOCKED", ip, "email=" + email);
            return ResponseMessage.error(requestId, lockMessage(locked));
        }

        try {
            Optional<String> storedHash = accountDAO.findPasswordHashByEmail(email);
            boolean valid;
            if (storedHash.isEmpty()) {
                PasswordHasher.burnTime(password); // thời gian phản hồi như khi email tồn tại
                valid = false;
            } else {
                valid = PasswordHasher.verify(password, storedHash.get());
            }
            if (!valid) {
                boolean nowLocked = loginThrottle.recordFailure(email, ip);
                SecurityLog.event(nowLocked ? "LOGIN_LOCKED" : "LOGIN_FAILED", ip, "email=" + email);
                return ResponseMessage.error(requestId, nowLocked
                        ? lockMessage(loginThrottle.lockRemaining(email, ip))
                        : INVALID_CREDENTIALS);
            }

            Optional<Account> account = accountDAO.findByEmail(email);
            if (account.isEmpty()) {
                return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
            }
            int accountId = account.get().getAccountId();

            // Bước 2 (nếu bật): mã TOTP 6 chữ số từ ứng dụng xác thực trên điện thoại.
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            if (settings.isPresent() && settings.get().totpEnabled()) {
                String code = getString(values, "totpCode").trim();
                if (code.isEmpty()) {
                    return ResponseMessage.error(requestId, ErrorCodes.TOTP_REQUIRED,
                            "Nhập mã xác thực 2 lớp từ ứng dụng trên điện thoại.");
                }
                long step = Totp.verify(settings.get().totpSecret(), code, System.currentTimeMillis() / 1000);
                if (step < 0 || !accountDAO.claimTotpStep(accountId, step)) {
                    boolean nowLocked = loginThrottle.recordFailure(email, ip);
                    SecurityLog.event("LOGIN_TOTP_FAILED", ip, "accountId=" + accountId);
                    return nowLocked
                            ? ResponseMessage.error(requestId, lockMessage(loginThrottle.lockRemaining(email, ip)))
                            : ResponseMessage.error(requestId, ErrorCodes.TOTP_REQUIRED,
                                    "Mã xác thực không đúng hoặc đã được dùng. Hãy nhập mã mới.");
                }
            }

            loginThrottle.recordSuccess(email, ip);
            SecurityLog.event("LOGIN", ip, "accountId=" + accountId);
            return ResponseMessage.ok(requestId, sessionData(account.get()));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể truy cập cơ sở dữ liệu.");
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
        }
    }

    /** RESUME_SESSION {resumeToken}: đăng nhập lại sau khi rớt mạng mà không cần mật khẩu. */
    public ResponseMessage resume(String requestId, JsonElement payload, String ip) {
        String token = payload != null && payload.isJsonObject() ? getString(payload.getAsJsonObject(), "resumeToken") : "";
        int accountId = ResumeTokens.get().redeem(token);
        if (accountId < 0) {
            SecurityLog.event("RESUME_REJECTED", ip, "");
            return ResponseMessage.error(requestId, ErrorCodes.RESUME_REJECTED,
                    "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.");
        }
        try {
            Optional<Account> account = accountDAO.findById(accountId);
            if (account.isEmpty()) {
                return ResponseMessage.error(requestId, ErrorCodes.RESUME_REJECTED, "Tài khoản không còn tồn tại.");
            }
            SecurityLog.event("RESUME", ip, "accountId=" + accountId);
            return ResponseMessage.ok(requestId, sessionData(account.get()));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể truy cập cơ sở dữ liệu.");
        }
    }

    /** Dữ liệu trả về khi đăng nhập / tiếp tục phiên (router thêm resumeToken). */
    private JsonObject sessionData(Account account) throws SQLException {
        List<Folder> folders = folderDAO.findFoldersByAccount(account.getAccountId());
        JsonObject data = new JsonObject();
        data.addProperty("accountId", account.getAccountId());
        data.addProperty("email", account.getEmail());
        data.addProperty("displayName", account.getDisplayName());
        JsonArray folderData = ProtocolUtil.getGson().toJsonTree(folders).getAsJsonArray();
        data.add("folders", folderData);
        return data;
    }

    static String lockMessage(Duration remaining) {
        long minutes = Math.max(1, (remaining.getSeconds() + 59) / 60);
        return "Đăng nhập sai quá nhiều lần. Vui lòng thử lại sau " + minutes + " phút.";
    }

    static boolean containsControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    static String getString(JsonObject values, String property) {
        if (!values.has(property) || !values.get(property).isJsonPrimitive()
                || !values.get(property).getAsJsonPrimitive().isString()) {
            return "";
        }
        return values.get(property).getAsString();
    }

    private boolean isConstraintViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
