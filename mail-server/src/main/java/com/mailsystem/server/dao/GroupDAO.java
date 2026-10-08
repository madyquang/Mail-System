package com.mailsystem.server.dao;

import com.mailsystem.common.model.MailGroupDTO;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * DAO cho bang `mail_group`, `group_member`. TODO (TV2): implement bang JDBC.
 */
public interface GroupDAO {

    int insertGroup(String groupName, int ownerId) throws SQLException;

    int createGroupWithOwner(String groupName, int ownerId) throws SQLException;

    void insertMember(int groupId, int accountId) throws SQLException;

    void removeMember(int groupId, int accountId) throws SQLException;

    void deleteGroup(int groupId) throws SQLException;

    /**
     * Tra ve owner_id cua group, dung de kiem tra quyen (chi owner moi duoc
     * xoa/them/bot thanh vien).
     */
    Optional<Integer> findOwnerId(int groupId) throws SQLException;

    List<MailGroupDTO> findGroupsByMember(int accountId) throws SQLException;

    /**
     * Mo rong 1 group thanh danh sach accountId thanh vien - dung khi SEND_MAIL gui
     * toi 1 group.
     */
    List<Integer> getMemberAccountIds(int groupId) throws SQLException;

    Optional<Integer> findGroupIdByName(String groupName) throws SQLException;

    boolean isMember(int groupId, int accountId) throws SQLException;
}
