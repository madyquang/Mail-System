// Gói "service": logic nghiệp vụ phía Server.
package com.mailsystem.server.service;

// ProtocolUtil (Gson dùng chung), ResponseMessage (câu trả lời).
import com.mailsystem.common.protocol.ProtocolUtil;
import com.mailsystem.common.protocol.ResponseMessage;
// Kiểu JSON của Gson.
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// EVENT gửi về Client và tên sự kiện.
import com.mailsystem.common.protocol.EventMessage;
import com.mailsystem.common.protocol.EventName;
// Giới hạn số thư mục, độ dài tên.
import com.mailsystem.common.protocol.ProtocolConstants;
// DAO thư mục.
import com.mailsystem.server.dao.FolderDAO;
// Đẩy EVENT tới các phiên online.
import com.mailsystem.server.session.SessionManager;
import com.mailsystem.server.dao.jdbc.JdbcFolderDAO;

// SQLException: lỗi CSDL.
import java.sql.SQLException;

/** Xử lý các lệnh về thư mục: lấy danh sách, tạo, đổi tên, xoá thư mục tự tạo. */
public class FolderService {

    // Nơi truy vấn bảng folder.
    private final FolderDAO folderDAO;

    /** Constructor dùng khi chạy thật: DAO bằng JDBC. */
    public FolderService() {
        this(new JdbcFolderDAO());
    }

    // Constructor nhận DAO từ ngoài (unit test).
    FolderService(FolderDAO folderDAO) {
        this.folderDAO = folderDAO;
    }

    /** CREATE_FOLDER {name} → {folderId}. */
    public ResponseMessage createFolder(String requestId, Integer accountId, JsonElement payload) {
        // Đọc và kiểm tra tên.
        String name = folderName(payload);
        if (name == null) {
            return ResponseMessage.error(requestId, "Tên thư mục phải từ 1 đến 100 ký tự.");
        }
        try {
            // Đã đủ số thư mục tự tạo tối đa.
            if (folderDAO.countCustom(accountId) >= ProtocolConstants.MAX_CUSTOM_FOLDERS) {
                return ResponseMessage.error(requestId, "Tối đa " + ProtocolConstants.MAX_CUSTOM_FOLDERS + " thư mục tự tạo.");
            }
            // Tạo thư mục.
            int folderId = folderDAO.createCustom(accountId, name);
            // Báo các máy đang mở của tài khoản tải lại thanh bên.
            notifyChanged(accountId);
            // Trả mã thư mục mới.
            JsonObject data = new JsonObject();
            data.addProperty("folderId", folderId);
            return ResponseMessage.ok(requestId, data);
        } catch (SQLException error) {
            // Trùng tên (vi phạm UNIQUE) hoặc lỗi khác.
            return duplicateOrFailure(requestId, error, "Không tạo được thư mục.");
        }
    }

    /** RENAME_FOLDER {folderId, name}. */
    public ResponseMessage renameFolder(String requestId, Integer accountId, JsonElement payload) {
        // Đọc mã và tên mới.
        Integer folderId = folderId(payload);
        String name = folderName(payload);
        if (folderId == null || name == null) {
            return ResponseMessage.error(requestId, "Thư mục hoặc tên mới không hợp lệ.");
        }
        try {
            // Chỉ đổi được thư mục CUSTOM của chính mình (DAO trả false nếu không phải).
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
            // DAO trả số thư đã chuyển vào Thùng rác, hoặc số âm nếu không phải thư mục CUSTOM của mình.
            int moved = folderDAO.deleteCustom(accountId, folderId);
            if (moved < 0) {
                return ResponseMessage.error(requestId, "Chỉ xoá được thư mục tự tạo của bạn.");
            }
            // Thanh bên thay đổi.
            notifyChanged(accountId);
            // Có thư bị chuyển thì báo danh sách thư cũng thay đổi.
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

    // Đọc tên thư mục từ payload; null nếu thiếu, rỗng, quá dài hoặc chứa ký tự điều khiển.
    private static String folderName(JsonElement payload) {
        if (payload == null || !payload.isJsonObject() || !payload.getAsJsonObject().has("name")) {
            return null;
        }
        // Bỏ khoảng trắng hai đầu.
        String name = payload.getAsJsonObject().get("name").getAsString().trim();
        return name.isEmpty() || name.length() > ProtocolConstants.MAX_FOLDER_NAME_CHARS
                || name.chars().anyMatch(Character::isISOControl) ? null : name;
    }

    // Đọc folderId dương từ payload; null nếu thiếu hoặc sai kiểu.
    private static Integer folderId(JsonElement payload) {
        try {
            int id = payload.getAsJsonObject().get("folderId").getAsInt();
            return id > 0 ? id : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    // SQLState bắt đầu bằng "23" là vi phạm ràng buộc (ở đây là trùng tên thư mục).
    private static ResponseMessage duplicateOrFailure(String requestId, SQLException error, String fallback) {
        return error.getSQLState() != null && error.getSQLState().startsWith("23")
                ? ResponseMessage.error(requestId, "Đã có thư mục cùng tên.")
                : ResponseMessage.error(requestId, fallback);
    }

    // Đẩy EVENT FOLDERS_UPDATED tới mọi phiên của tài khoản.
    private static void notifyChanged(int accountId) {
        SessionManager.getInstance().pushEvent(accountId, new EventMessage(EventName.FOLDERS_UPDATED, new JsonObject()));
    }

    /** GET_FOLDERS → danh sách Folder (kèm số thư chưa đọc). */
    public ResponseMessage getFolders(String requestId, Integer accountId) {
        // Phòng hờ (CommandRouter đã chặn lệnh này khi chưa đăng nhập).
        if (accountId == null) {
            return ResponseMessage.error(requestId, "Ban chua dang nhap");
        }
        try {
            // Đọc danh sách và đổi thành JSON.
            return ResponseMessage.ok(requestId,
                    ProtocolUtil.getGson().toJsonTree(folderDAO.findFoldersByAccount(accountId)));
        } catch (SQLException error) {
            return ResponseMessage.error(requestId, "Không thể tải danh sách thư mục.");
        }
    }
}
