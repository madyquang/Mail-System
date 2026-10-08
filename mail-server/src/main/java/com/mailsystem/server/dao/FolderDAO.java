package com.mailsystem.server.dao;

import com.mailsystem.common.model.Folder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** DAO cho bang `folder`. TODO (TV2): implement bang JDBC. */
public interface FolderDAO {

    /** Tao san 3 folder mac dinh (INBOX, SENT, TRASH) khi REGISTER thanh cong. */
    void createDefaultFolders(int accountId) throws SQLException;

    void createDefaultFolders(Connection connection, int accountId) throws SQLException;

    List<Folder> findFoldersByAccount(int accountId) throws SQLException;

    /**
     * Lay folderId cua 1 loai folder mac dinh (VD: "INBOX") cua 1 account -
     * can dung khi SEND_MAIL (bo vao SENT cua sender, INBOX cua recipient).
     */
    Optional<Integer> findFolderIdByType(int accountId, String folderType) throws SQLException;

    Optional<Integer> findFolderIdByType(Connection connection, int accountId, String folderType)
            throws SQLException;
}
