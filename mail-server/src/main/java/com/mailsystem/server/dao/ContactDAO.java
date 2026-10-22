package com.mailsystem.server.dao;

import com.mailsystem.common.model.Contact;

import java.sql.SQLException;
import java.util.List;

/** Tra cứu gợi ý người nhận. Chỉ đọc. */
public interface ContactDAO {

    /**
     * Người có email hoặc tên hiển thị chứa pattern (đã escape cho LIKE, ký tự
     * escape '!'), trừ chính người dùng. Người đã trao đổi thư nhiều xếp trước,
     * rồi tới người khớp từ đầu email.
     */
    List<Contact> findPeople(int accountId, String containsPattern, String prefixPattern, int limit)
            throws SQLException;

    /** Nhóm mà người dùng là thành viên và tên nhóm chứa pattern. */
    List<Contact> findGroups(int accountId, String containsPattern, int limit) throws SQLException;
}
