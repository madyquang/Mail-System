package com.mailsystem.client.ui;

import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Bộ icon dùng chung (Material Design 2 qua Ikonli). Gom tên icon vào một chỗ
 * để toàn ứng dụng nhất quán và dễ đổi bộ icon.
 */
public final class Icons {

    public static final String INBOX = "mdi2i-inbox";
    public static final String STAR = "mdi2s-star";
    public static final String STAR_OUTLINE = "mdi2s-star-outline";
    public static final String SENT = "mdi2s-send-outline";
    public static final String DRAFT = "mdi2f-file-document-edit-outline";
    public static final String TRASH = "mdi2d-delete-outline";
    public static final String DELETE_FOREVER = "mdi2d-delete-forever-outline";
    public static final String COMPOSE = "mdi2p-pencil-outline";
    public static final String SEARCH = "mdi2m-magnify";
    public static final String REFRESH = "mdi2r-refresh";
    public static final String GROUPS = "mdi2a-account-group-outline";
    public static final String SETTINGS = "mdi2c-cog-outline";
    public static final String LOGOUT = "mdi2l-logout";
    public static final String REPLY = "mdi2r-reply";
    public static final String REPLY_ALL = "mdi2r-reply-all";
    public static final String FORWARD = "mdi2s-share-outline";
    public static final String READ = "mdi2e-email-open-outline";
    public static final String UNREAD = "mdi2e-email-mark-as-unread";
    public static final String MAIL = "mdi2e-email-outline";
    public static final String ATTACH = "mdi2p-paperclip";
    public static final String LOCK = "mdi2l-lock-outline";
    public static final String SHIELD = "mdi2s-shield-check-outline";
    public static final String RESTORE = "mdi2r-restore";
    public static final String CLOSE = "mdi2c-close";
    public static final String MENU = "mdi2m-menu";
    public static final String DOWNLOAD = "mdi2d-download";
    public static final String SAVE = "mdi2c-content-save-outline";
    public static final String EYE = "mdi2e-eye-outline";
    public static final String EYE_OFF = "mdi2e-eye-off-outline";
    public static final String SUCCESS = "mdi2c-check-circle-outline";
    public static final String ERROR = "mdi2a-alert-circle-outline";
    public static final String INFO = "mdi2i-information-outline";
    public static final String ACCOUNT = "mdi2a-account-circle-outline";
    public static final String SERVER = "mdi2s-server-network";
    public static final String ADD_MEMBER = "mdi2a-account-plus-outline";
    public static final String REMOVE_MEMBER = "mdi2a-account-remove-outline";
    public static final String LEAVE = "mdi2e-exit-run";
    public static final String PLUS = "mdi2p-plus";
    public static final String KEY = "mdi2k-key-outline";
    public static final String DEVICES = "mdi2d-devices";
    public static final String BELL = "mdi2b-bell-outline";
    public static final String OFFLINE = "mdi2c-cloud-off-outline";
    public static final String SELECT_ALL = "mdi2c-checkbox-multiple-marked-outline";
    public static final String FILE = "mdi2f-file-document-outline";
    public static final String HISTORY = "mdi2h-history";
    public static final String PERSON = "mdi2a-account-outline";
    public static final String MAIL_SEARCH = "mdi2e-email-search-outline";
    public static final String PERSON_SEARCH = "mdi2a-account-search-outline";
    public static final String FILE_PDF = "mdi2f-file-pdf-box";
    public static final String FILE_WORD = "mdi2f-file-word-box";
    public static final String FILE_SLIDES = "mdi2f-file-powerpoint-box";
    public static final String FILE_IMAGE = "mdi2f-file-image-outline";
    public static final String FILE_ZIP = "mdi2f-folder-zip-outline";

    private Icons() {
    }

    public static FontIcon of(String literal) {
        return of(literal, 18);
    }

    public static FontIcon of(String literal, int size) {
        FontIcon icon = new FontIcon(literal);
        icon.setIconSize(size);
        icon.getStyleClass().add("icon");
        return icon;
    }

    /** Icon theo loại tệp đính kèm. */
    public static String forFile(String filename) {
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".pdf")) {
            return FILE_PDF;
        }
        if (name.endsWith(".docx")) {
            return FILE_WORD;
        }
        if (name.endsWith(".pptx")) {
            return FILE_SLIDES;
        }
        if (name.endsWith(".png") || name.endsWith(".jpg")) {
            return FILE_IMAGE;
        }
        if (name.endsWith(".zip")) {
            return FILE_ZIP;
        }
        return FILE;
    }
}
