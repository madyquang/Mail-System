package com.mailsystem.server.dao;

import com.mailsystem.common.model.Account;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * DAO cho bang `account`. CHI chua cau lenh JDBC thuan, KHONG chua logic
 * nghiep vu (VD: kiem tra password dung/sai la viec cua AuthService, khong
 * phai cua DAO).
 *
 * TODO (TV2): implement bang JDBC (PreparedStatement) dua tren schema.sql.
 */
public interface AccountDAO {

    /**
     * Insert account moi, tra ve accountId vua tao (dung
     * Statement.RETURN_GENERATED_KEYS).
     */
    int insertAccount(String email, String passwordHash, String displayName) throws SQLException;

    int insertAccount(Connection connection, String email, String passwordHash, String displayName)
            throws SQLException;

    /**
     * Tim account theo email. Dung cho LOGIN va kiem tra nguoi nhan ton tai khi
     * SEND_MAIL.
     */
    Optional<Account> findByEmail(String email) throws SQLException;

    /**
     * Lay password_hash theo email, dung rieng cho AuthService so sanh khi LOGIN.
     */
    Optional<String> findPasswordHashByEmail(String email) throws SQLException;

    Optional<Account> findById(int accountId) throws SQLException;

    boolean existsByEmail(String email) throws SQLException;

    boolean existsByEmail(Connection connection, String email) throws SQLException;
}
