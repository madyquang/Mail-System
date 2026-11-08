// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// JSON, DTO tài khoản / thư mục, mã lỗi, hằng số, Gson, câu trả lời.
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.protocol.ErrorCodes;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
// DAO tài khoản và thư mục.
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.jdbc.JdbcAccountDAO;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;
// Kết nối CSDL (để chạy transaction khi đăng ký).
import com.mailsystem.server.db.DatabaseConnection;
// Bảo mật.
import com.mailsystem.server.security.LoginThrottle;
import com.mailsystem.server.security.PasswordHasher;
import com.mailsystem.server.security.PasswordPolicy;
import com.mailsystem.server.security.SecurityLog;
import com.mailsystem.server.security.SlidingWindowLimiter;
import com.mailsystem.server.security.Totp;
import com.mailsystem.server.session.ResumeTokens;

// Lỗi mật mã, JDBC, thời gian, tập hợp.
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

    // Câu báo lỗi chung cho mọi trường hợp đăng nhập sai (không phân biệt sai email hay sai mật khẩu).
    static final String INVALID_CREDENTIALS = "Email hoặc mật khẩu không đúng";
    // Mẫu email đơn giản: "phần@phần.phần", không có khoảng trắng.
    private static final String EMAIL_PATTERN = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

    // DAO và các bộ giới hạn.
    private final AccountDAO accountDAO;
    private final FolderDAO folderDAO;
    private final LoginThrottle loginThrottle;
    private final SlidingWindowLimiter registrationLimiter;

    /** Constructor dùng khi chạy thật: DAO bằng JDBC. */
    public AuthService(LoginThrottle loginThrottle, SlidingWindowLimiter registrationLimiter) {
        this(new JdbcAccountDAO(), new JdbcFolderDAO(), loginThrottle, registrationLimiter);
    }

    // Constructor nhận DAO từ ngoài (unit test).
    AuthService(AccountDAO accountDAO, FolderDAO folderDAO, LoginThrottle loginThrottle,
            SlidingWindowLimiter registrationLimiter) {
        this.accountDAO = accountDAO;
        this.folderDAO = folderDAO;
        this.loginThrottle = loginThrottle;
        this.registrationLimiter = registrationLimiter;
    }

    /** REGISTER {email, displayName, password} → {accountId, email, displayName}. */
    public ResponseMessage register(String requestId, JsonElement payload, String ip) {
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, "Thông tin đăng ký không hợp lệ.");
        }
        JsonObject values = payload.getAsJsonObject();
        // Email chuẩn hoá: bỏ khoảng trắng, chữ thường.
        String email = getString(values, "email").trim().toLowerCase(Locale.ROOT);
        String displayName = getString(values, "displayName").trim();
        String password = getString(values, "password");

        // Thiếu trường.
        if (email.isBlank() || displayName.isBlank() || password.isBlank()) {
            return ResponseMessage.error(requestId, "Vui lòng điền đầy đủ thông tin đăng ký.");
        }
        // Email quá dài (cột VARCHAR(150)) hoặc sai định dạng.
        if (email.length() > 150 || !email.matches(EMAIL_PATTERN)) {
            return ResponseMessage.error(requestId, "Địa chỉ email chưa đúng định dạng.");
        }
        // Tên quá dài hoặc có ký tự điều khiển.
        if (displayName.length() > ProtocolConstants.MAX_DISPLAY_NAME_CHARS || containsControl(displayName)) {
            return ResponseMessage.error(requestId, "Tên hiển thị tối đa 100 ký tự và không chứa ký tự điều khiển.");
        }
        // Mật khẩu phải đạt chính sách.
        String policyError = PasswordPolicy.validate(password, email);
        if (policyError != null) {
            return ResponseMessage.error(requestId, policyError);
        }
        // Giới hạn số lần đăng ký mỗi IP mỗi giờ.
        if (!registrationLimiter.tryAcquire(ip)) {
            SecurityLog.event("REGISTER_RATE_LIMITED", ip, "email=" + email);
            return ResponseMessage.error(requestId, "Có quá nhiều lượt đăng ký từ máy này, vui lòng thử lại sau.");
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            // Tạo tài khoản + 3 thư mục trong một transaction.
            connection.setAutoCommit(false);
            try {
                // Email đã có.
                if (accountDAO.existsByEmail(connection, email)) {
                    connection.rollback();
                    return ResponseMessage.error(requestId, "Email này đã được đăng ký.");
                }
                // Thêm tài khoản với mật khẩu đã băm.
                int accountId = accountDAO.insertAccount(connection, email, PasswordHasher.hash(password), displayName);
                // Tạo INBOX, SENT, TRASH.
                folderDAO.createDefaultFolders(connection, accountId);
                connection.commit();
                SecurityLog.event("REGISTER", ip, "accountId=" + accountId);

                // Trả thông tin tài khoản vừa tạo.
                JsonObject data = new JsonObject();
                data.addProperty("accountId", accountId);
                data.addProperty("email", email);
                data.addProperty("displayName", displayName);
                return ResponseMessage.ok(requestId, data);
            } catch (SQLException | GeneralSecurityException error) {
                connection.rollback();
                // Hai người đăng ký cùng email cùng lúc: một người dính lỗi trùng khoá UNIQUE.
                if (error instanceof SQLException sqlError && isConstraintViolation(sqlError)) {
                    return ResponseMessage.error(requestId, "Email này đã được đăng ký.");
                }
                return ResponseMessage.error(requestId, "Không thể tạo tài khoản lúc này.");
            }
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể kết nối cơ sở dữ liệu.");
        }
    }

    /** LOGIN {email, password, totpCode?} → thông tin phiên (accountId, email, displayName, folders). */
    public ResponseMessage login(String requestId, JsonElement payload, String ip) {
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
        }
        JsonObject values = payload.getAsJsonObject();
        String email = getString(values, "email").trim().toLowerCase(Locale.ROOT);
        String password = getString(values, "password");
        // Thiếu dữ liệu hoặc mật khẩu quá dài (chặn tốn CPU khi băm).
        if (email.isBlank() || password.isBlank() || password.length() > ProtocolConstants.MAX_PASSWORD_LENGTH) {
            return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
        }

        // ① Đang bị khoá vì sai nhiều lần: từ chối ngay, không kiểm tra mật khẩu.
        Duration locked = loginThrottle.lockRemaining(email, ip);
        if (!locked.isZero()) {
            SecurityLog.event("LOGIN_BLOCKED", ip, "email=" + email);
            return ResponseMessage.error(requestId, lockMessage(locked));
        }

        try {
            // ② So mật khẩu.
            Optional<String> storedHash = accountDAO.findPasswordHashByEmail(email);
            boolean valid;
            if (storedHash.isEmpty()) {
                PasswordHasher.burnTime(password); // thời gian phản hồi như khi email tồn tại
                valid = false;
            } else {
                valid = PasswordHasher.verify(password, storedHash.get());
            }
            // Sai: đếm một lần sai; nếu vừa bị khoá thì báo thời gian khoá.
            if (!valid) {
                boolean nowLocked = loginThrottle.recordFailure(email, ip);
                SecurityLog.event(nowLocked ? "LOGIN_LOCKED" : "LOGIN_FAILED", ip, "email=" + email);
                return ResponseMessage.error(requestId, nowLocked
                        ? lockMessage(loginThrottle.lockRemaining(email, ip))
                        : INVALID_CREDENTIALS);
            }

            // Lấy thông tin tài khoản.
            Optional<Account> account = accountDAO.findByEmail(email);
            if (account.isEmpty()) {
                return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
            }
            int accountId = account.get().getAccountId();

            // Bước 2 (nếu bật): mã TOTP 6 chữ số từ ứng dụng xác thực trên điện thoại.
            Optional<AccountDAO.SecuritySettings> settings = accountDAO.findSecuritySettings(accountId);
            if (settings.isPresent() && settings.get().totpEnabled()) {
                String code = getString(values, "totpCode").trim();
                // Chưa gửi mã: trả mã lỗi TOTP_REQUIRED để Client hiện ô nhập mã.
                if (code.isEmpty()) {
                    return ResponseMessage.error(requestId, ErrorCodes.TOTP_REQUIRED,
                            "Nhập mã xác thực 2 lớp từ ứng dụng trên điện thoại.");
                }
                // Kiểm tra mã và đánh dấu mã đã dùng (không dùng lại được).
                long step = Totp.verify(settings.get().totpSecret(), code, System.currentTimeMillis() / 1000);
                if (step < 0 || !accountDAO.claimTotpStep(accountId, step)) {
                    // Mã sai cũng tính là một lần đăng nhập sai.
                    boolean nowLocked = loginThrottle.recordFailure(email, ip);
                    SecurityLog.event("LOGIN_TOTP_FAILED", ip, "accountId=" + accountId);
                    return nowLocked
                            ? ResponseMessage.error(requestId, lockMessage(loginThrottle.lockRemaining(email, ip)))
                            : ResponseMessage.error(requestId, ErrorCodes.TOTP_REQUIRED,
                                    "Mã xác thực không đúng hoặc đã được dùng. Hãy nhập mã mới.");
                }
            }

            // ③ Thành công: xoá bộ đếm sai, ghi nhật ký, trả thông tin phiên.
            loginThrottle.recordSuccess(email, ip);
            SecurityLog.event("LOGIN", ip, "accountId=" + accountId);
            return ResponseMessage.ok(requestId, sessionData(account.get()));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể truy cập cơ sở dữ liệu.");
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            // Lỗi băm hoặc khoá TOTP hỏng: coi như sai thông tin.
            return ResponseMessage.error(requestId, INVALID_CREDENTIALS);
        }
    }

    /** RESUME_SESSION {resumeToken}: đăng nhập lại sau khi rớt mạng mà không cần mật khẩu. */
    public ResponseMessage resume(String requestId, JsonElement payload, String ip) {
        // Đọc token (thiếu thì chuỗi rỗng).
        String token = payload != null && payload.isJsonObject() ? getString(payload.getAsJsonObject(), "resumeToken") : "";
        // Dùng token (một lần): trả accountId hoặc -1.
        int accountId = ResumeTokens.get().redeem(token);
        if (accountId < 0) {
            SecurityLog.event("RESUME_REJECTED", ip, "");
            return ResponseMessage.error(requestId, ErrorCodes.RESUME_REJECTED,
                    "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.");
        }
        try {
            // Tài khoản có thể đã bị xoá trong lúc mất kết nối.
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
        // Danh sách thư mục kèm số thư chưa đọc.
        List<Folder> folders = folderDAO.findFoldersByAccount(account.getAccountId());
        JsonObject data = new JsonObject();
        data.addProperty("accountId", account.getAccountId());
        data.addProperty("email", account.getEmail());
        data.addProperty("displayName", account.getDisplayName());
        // Đổi danh sách thư mục thành mảng JSON.
        JsonArray folderData = ProtocolUtil.getGson().toJsonTree(folders).getAsJsonArray();
        data.add("folders", folderData);
        return data;
    }

    /** Câu báo đang bị khoá, làm tròn lên theo phút (tối thiểu 1 phút). */
    static String lockMessage(Duration remaining) {
        // (giây + 59) / 60: chia lấy phần nguyên làm tròn lên.
        long minutes = Math.max(1, (remaining.getSeconds() + 59) / 60);
        return "Đăng nhập sai quá nhiều lần. Vui lòng thử lại sau " + minutes + " phút.";
    }

    /** @return true nếu chuỗi có ký tự điều khiển (xuống dòng, tab, ký tự 0...) */
    static boolean containsControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    /** Đọc một trường chuỗi của JSON; thiếu hoặc không phải chuỗi thì trả "". */
    static String getString(JsonObject values, String property) {
        if (!values.has(property) || !values.get(property).isJsonPrimitive()
                || !values.get(property).getAsJsonPrimitive().isString()) {
            return "";
        }
        return values.get(property).getAsString();
    }

    // Lỗi SQL có phải vi phạm ràng buộc (SQLState bắt đầu "23") không; duyệt cả chuỗi lỗi nối tiếp.
    private boolean isConstraintViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
