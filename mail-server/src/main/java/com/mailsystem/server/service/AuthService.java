package com.mailsystem.server.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mailsystem.common.model.Account;
import com.mailsystem.common.model.Folder;
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.AccountDAO;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.JdbcAccountDAO;
import com.mailsystem.server.dao.JdbcFolderDAO;
import com.mailsystem.server.db.DatabaseConnection;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.List;
import java.util.Optional;

/**
 * Xu ly nghiep vu REGISTER / LOGIN / LOGOUT. Password hash dùng PBKDF2 và salt
 * ngẫu nhiên.
 */
public class AuthService {

    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final AccountDAO accountDAO;
    private final FolderDAO folderDAO;

    public AuthService() {
        this(new JdbcAccountDAO(), new JdbcFolderDAO());
    }

    AuthService(AccountDAO accountDAO, FolderDAO folderDAO) {
        this.accountDAO = accountDAO;
        this.folderDAO = folderDAO;
    }

    public ResponseMessage register(String requestId, JsonElement payload) {
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
        if (email.length() > 150 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            return ResponseMessage.error(requestId, "Địa chỉ email chưa đúng định dạng.");
        }
        if (displayName.length() > 100) {
            return ResponseMessage.error(requestId, "Tên hiển thị không được vượt quá 100 ký tự.");
        }
        if (password.length() < 6) {
            return ResponseMessage.error(requestId, "Mật khẩu cần có ít nhất 6 ký tự.");
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (accountDAO.existsByEmail(connection, email)) {
                    connection.rollback();
                    return ResponseMessage.error(requestId, "Email này đã được đăng ký.");
                }

                String passwordHash = hashPassword(password);
                int accountId = accountDAO.insertAccount(connection, email, passwordHash, displayName);
                folderDAO.createDefaultFolders(connection, accountId);
                connection.commit();

                JsonObject data = new JsonObject();
                data.addProperty("accountId", accountId);
                data.addProperty("email", email);
                data.addProperty("displayName", displayName);
                return ResponseMessage.ok(requestId, data);
            } catch (SQLException | GeneralSecurityException error) {
                connection.rollback();
                if (error instanceof SQLException sqlError && isConstraintViolation(sqlError)
                        && accountDAO.existsByEmail(connection, email)) {
                    return ResponseMessage.error(requestId, "Email này đã được đăng ký.");
                }
                return ResponseMessage.error(requestId, "Không thể tạo tài khoản lúc này.");
            }
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể kết nối cơ sở dữ liệu.");
        }
    }

    public ResponseMessage login(String requestId, JsonElement payload) {
        String invalidCredentials = "Email hoặc mật khẩu không đúng";
        if (payload == null || !payload.isJsonObject()) {
            return ResponseMessage.error(requestId, invalidCredentials);
        }

        JsonObject values = payload.getAsJsonObject();
        String email = getString(values, "email").trim().toLowerCase(Locale.ROOT);
        String password = getString(values, "password");
        if (email.isBlank() || password.isBlank()) {
            return ResponseMessage.error(requestId, invalidCredentials);
        }

        try {
            Optional<String> storedHash = accountDAO.findPasswordHashByEmail(email);
            if (storedHash.isEmpty()) {
                hashPassword(password);
                return ResponseMessage.error(requestId, invalidCredentials);
            }
            if (!verifyPassword(password, storedHash.get())) {
                return ResponseMessage.error(requestId, invalidCredentials);
            }

            Optional<Account> account = accountDAO.findByEmail(email);
            if (account.isEmpty()) {
                return ResponseMessage.error(requestId, invalidCredentials);
            }

            List<Folder> folders = folderDAO.findFoldersByAccount(account.get().getAccountId());
            JsonObject data = new JsonObject();
            data.addProperty("accountId", account.get().getAccountId());
            data.addProperty("email", account.get().getEmail());
            data.addProperty("displayName", account.get().getDisplayName());
            JsonArray folderData = ProtocolUtil.getGson().toJsonTree(folders).getAsJsonArray();
            data.add("folders", folderData);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể truy cập cơ sở dữ liệu.");
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            return ResponseMessage.error(requestId, invalidCredentials);
        }
    }

    private String getString(JsonObject values, String property) {
        if (!values.has(property) || !values.get(property).isJsonPrimitive()
                || !values.get(property).getAsJsonPrimitive().isString()) {
            return "";
        }
        return values.get(property).getAsString();
    }

    private String hashPassword(String password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        char[] passwordChars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(passwordChars, salt, PASSWORD_ITERATIONS, HASH_BITS);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            Base64.Encoder encoder = Base64.getEncoder();
            return "pbkdf2_sha256$" + PASSWORD_ITERATIONS + "$"
                    + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
        } finally {
            spec.clearPassword();
            Arrays.fill(passwordChars, '\0');
        }
    }

    private boolean verifyPassword(String password, String encodedHash) throws GeneralSecurityException {
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }

        int iterations;
        byte[] salt;
        byte[] expectedHash;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expectedHash = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException error) {
            return false;
        }
        if (iterations < 10_000 || iterations > 1_000_000 || salt.length != SALT_BYTES
                || expectedHash.length != HASH_BITS / Byte.SIZE) {
            return false;
        }

        char[] passwordChars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(passwordChars, salt, iterations, expectedHash.length * Byte.SIZE);
        try {
            byte[] actualHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expectedHash, actualHash);
        } finally {
            spec.clearPassword();
            Arrays.fill(passwordChars, '\0');
        }
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
