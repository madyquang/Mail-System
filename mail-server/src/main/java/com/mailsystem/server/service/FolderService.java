package com.mailsystem.server.service;

import com.mailsystem.common.protocol.ResponseMessage;

/** TODO (TV2): goi FolderDAO.findFoldersByAccount(accountId), tra ve list Folder trong data. */
public class FolderService {

    public ResponseMessage getFolders(String requestId, Integer accountId) {
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Ban chua dang nhap");
        }
        // TODO: implement
        return ResponseMessage.error(requestId, "Chua implement GET_FOLDERS");
    }
}
