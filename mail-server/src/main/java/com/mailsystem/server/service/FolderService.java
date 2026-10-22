package com.mailsystem.server.service;

import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
import com.mailsystem.common.protocol.ProtocolConstants;
import com.mailsystem.server.dao.FolderDAO;
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;

import java.sql.SQLException;

public class FolderService {

    private final FolderDAO folderDAO;

    public FolderService() {
        this(new JdbcFolderDAO());
    }

    FolderService(FolderDAO folderDAO) {
        this.folderDAO = folderDAO;
    }

    public ResponseMessage createFolder(String requestId, Integer accountId, JsonElement payload) {
        String name = folderName(payload);
        if (name == null) {
            return ResponseMessage.error(requestId, "Tên thư mục phải từ 1 đến 100 ký tự.");
        }
        try {
            if (folderDAO.countCustom(accountId) >= ProtocolConstants.MAX_CUSTOM_FOLDERS) {
                return ResponseMessage.error(requestId, "Tối đa " + ProtocolConstants.MAX_CUSTOM_FOLDERS + " thư mục tự tạo.");
            }
            int folderId = folderDAO.createCustom(accountId, name);
            notifyChanged(accountId);
            JsonObject data = new JsonObject();
            data.addProperty("folderId", folderId);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return duplicateOrFailure(requestId, error, "Không tạo được thư mục.");
        }
    }

    public ResponseMessage renameFolder(String requestId, Integer accountId, JsonElement payload) {
        Integer folderId = folderId(payload);
        String name = folderName(payload);
        if (folderId == null || name == null) {
            return ResponseMessage.error(requestId, "Thư mục hoặc tên mới không hợp lệ.");
        }
        try {
            if (!folderDAO.renameCustom(accountId, folderId, name)) {
                return ResponseMessage.error(requestId, "Chỉ đổi tên được thư mục tự tạo của bạn.");
            }
            notifyChanged(accountId);
            return ResponseMessage.ok(requestId, null);
        } catch (SQLException error) {
            return duplicateOrFailure(requestId, error, "Không đổi tên được thư mục.");
        }
    }

    /** Thư trong thư mục được chuyển vào Thùng rác (khôi phục về Hộp thư đến / Đã gửi). */
    public ResponseMessage deleteFolder(String requestId, Integer accountId, JsonElement payload) {
        Integer folderId = folderId(payload);
        if (folderId == null) {
            return ResponseMessage.error(requestId, "Thư mục không hợp lệ.");
        }
        try {
            int moved = folderDAO.deleteCustom(accountId, folderId);
            if (moved < 0) {
                return ResponseMessage.error(requestId, "Chỉ xoá được thư mục tự tạo của bạn.");
            }
            notifyChanged(accountId);
            if (moved > 0) {
                SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.MAIL_DELETED, new JsonObject()));
            }
            JsonObject data = new JsonObject();
            data.addProperty("movedToTrash", moved);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không xoá được thư mục.");
        }
    }

    private static String folderName(JsonElement payload) {
        if (payload == null || !payload.isJsonObject() || !payload.getAsJsonObject().has("name")) {
            return null;
        }
        String name = payload.getAsJsonObject().get("name").getAsString().trim();
        return name.isEmpty() || name.length() > ProtocolConstants.MAX_FOLDER_NAME_CHARS
                || name.chars().anyMatch(Character::isISOControl) ? null : name;
    }

    private static Integer folderId(JsonElement payload) {
        try {
            int id = payload.getAsJsonObject().get("folderId").getAsInt();
            return id > 0 ? id : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static ResponseMessage duplicateOrFailure(String requestId, SQLException error, String fallback) {
        return error.getSQLState() != null && error.getSQLState().startsWith("23")
                ? ResponseMessage.error(requestId, "Đã có thư mục cùng tên.")
                : ResponseMessage.error(requestId, fallback);
    }

    private static void notifyChanged(int accountId) {
        SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.FOLDERS_UPDATED, new JsonObject()));
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
