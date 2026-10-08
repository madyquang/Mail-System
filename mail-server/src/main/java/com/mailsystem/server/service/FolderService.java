package com.mailsystem.server.service;

import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.dao.JdbcFolderDAO;

import java.sql.SQLException;

public class FolderService {

    private final FolderDAO folderDAO;

    public FolderService() {
        this(new JdbcFolderDAO());
    }

    FolderService(FolderDAO folderDAO) {
        this.folderDAO = folderDAO;
    }

    public ResponseMessage getFolders(String requestId, Integer accountId) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Ban chua dang nhap");
        }
        try {
            return ResponseMessage.ok(requestId,
                    ProtocolUtil.getGson().toJsonTree(folderDAO.findFoldersByAccount(accountId)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tải danh sách thư mục.");
        }
    }
}
