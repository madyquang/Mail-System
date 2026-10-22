package com.mailsystem.server.dao;

import com.mailsystem.common.model.Draft;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** DAO cho bảng `draft` (thư nháp). Mọi thao tác đều giới hạn theo accountId chủ sở hữu. */
public interface DraftDAO {

    int insert(int accountId, Draft draft) throws SQLException;

    /** @return false nếu bản nháp không tồn tại hoặc không thuộc tài khoản */
    boolean update(int accountId, Draft draft) throws SQLException;

    List<Draft> findByAccount(int accountId) throws SQLException;

    Optional<Draft> findById(int accountId, int draftId) throws SQLException;

    /** @return số bản nháp đã xoá */
    int delete(int accountId, List<Integer> draftIds) throws SQLException;
}
