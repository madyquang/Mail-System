// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/**
 * Ứng với bảng `folder`. folderType: INBOX | SENT | TRASH | CUSTOM.
 * unreadCount: số thư chưa đọc trong thư mục (Server tính khi trả GET_FOLDERS).
 */
public class Folder {
    // Mã thư mục (khoá chính folder_id).
    private int folderId;
    // Tên hiển thị, vd "Hộp thư đến".
    private String folderName;
    // Loại thư mục: INBOX | SENT | TRASH | CUSTOM.
    private String folderType;
    // Số thư chưa đọc trong thư mục.
    private int unreadCount;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public Folder() {
    }

    /**
     * Tạo thư mục với mã, tên và loại (unreadCount mặc định 0, gán sau bằng setter).
     *
     * @param folderId   mã thư mục
     * @param folderName tên thư mục
     * @param folderType loại thư mục
     */
    public Folder(int folderId, String folderName, String folderType) {
        // Gán các tham số vào trường tương ứng.
        this.folderId = folderId;
        this.folderName = folderName;
        this.folderType = folderType;
    }

    // Getter / setter mã thư mục.
    public int getFolderId() { return folderId; }
    public void setFolderId(int folderId) { this.folderId = folderId; }

    // Getter / setter tên thư mục.
    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }

    // Getter / setter loại thư mục.
    public String getFolderType() { return folderType; }
    public void setFolderType(String folderType) { this.folderType = folderType; }

    // Getter / setter số thư chưa đọc.
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }
}
