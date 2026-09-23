package com.mailsystem.common.model;

/** Ứng với bảng `folder`. folderType: INBOX | SENT | TRASH | CUSTOM. */
public class Folder {
    private int folderId;
    private String folderName;
    private String folderType;

    public Folder() {
    }

    public Folder(int folderId, String folderName, String folderType) {
        this.folderId = folderId;
        this.folderName = folderName;
        this.folderType = folderType;
    }

    public int getFolderId() { return folderId; }
    public void setFolderId(int folderId) { this.folderId = folderId; }

    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }

    public String getFolderType() { return folderType; }
    public void setFolderType(String folderType) { this.folderType = folderType; }
}
