-- =========================================================
-- MAIL SYSTEM DATABASE SCHEMA (MySQL)
-- =========================================================

CREATE DATABASE IF NOT EXISTS mail_system
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE mail_system;

-- ---------------------------------------------------------
-- 1. ACCOUNT: thông tin tài khoản người dùng
-- ---------------------------------------------------------
CREATE TABLE account (
    account_id      INT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(150) NOT NULL UNIQUE,   -- địa chỉ mail nội bộ, VD: an@mail.local
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 2. FOLDER: mỗi user có bộ thư mục riêng
--    Khi REGISTER thành công -> tự tạo 3 folder mặc định:
--    INBOX, SENT, TRASH. User có thể tạo thêm CUSTOM.
-- ---------------------------------------------------------
CREATE TABLE folder (
    folder_id       INT AUTO_INCREMENT PRIMARY KEY,
    account_id      INT NOT NULL,
    folder_name     VARCHAR(100) NOT NULL,
    folder_type     ENUM('INBOX','SENT','TRASH','CUSTOM') NOT NULL DEFAULT 'CUSTOM',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    UNIQUE KEY uq_account_foldername (account_id, folder_name)
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 3. MAIL: nội dung thư — MỘT bản ghi duy nhất cho mỗi thư
--    dù gửi cho bao nhiêu người.
-- ---------------------------------------------------------
CREATE TABLE mail (
    mail_id         INT AUTO_INCREMENT PRIMARY KEY,
    sender_id       INT NOT NULL,
    subject         VARCHAR(255) NOT NULL DEFAULT '(Không có tiêu đề)',
    body            TEXT,
    sent_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES account(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 4. MAIL_RECIPIENT: bảng trung tâm — đại diện cho việc
--    "1 thư xuất hiện trong hộp thư của 1 người, ở 1 folder,
--     với vai trò và trạng thái đọc riêng của người đó".
--    Kể cả người GỬI cũng có 1 dòng ở đây (folder = SENT).
-- ---------------------------------------------------------
CREATE TABLE mail_recipient (
    entry_id        INT AUTO_INCREMENT PRIMARY KEY,
    mail_id         INT NOT NULL,
    account_id      INT NOT NULL,          -- hộp thư này thuộc về ai
    folder_id       INT NOT NULL,          -- đang nằm ở folder nào
    original_folder_id INT NULL,           -- thư mục trước khi chuyển vào TRASH
    recipient_type  ENUM('TO','CC','BCC','SENDER') NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    FOREIGN KEY (folder_id) REFERENCES folder(folder_id) ON DELETE CASCADE,
    FOREIGN KEY (original_folder_id) REFERENCES folder(folder_id) ON DELETE SET NULL,
    INDEX idx_account_folder (account_id, folder_id),   -- tăng tốc GET_MAIL_LIST
    INDEX idx_mail (mail_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 5. ATTACHMENT: file đính kèm của thư (lưu path, không lưu blob)
-- ---------------------------------------------------------
CREATE TABLE attachment (
    attachment_id   INT AUTO_INCREMENT PRIMARY KEY,
    mail_id         INT NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    storage_path    VARCHAR(500) NOT NULL,   -- đường dẫn file trên ổ đĩa Server
    FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 6. MAIL_GROUP: nhóm mail (mail group)
-- ---------------------------------------------------------
CREATE TABLE mail_group (
    group_id        INT AUTO_INCREMENT PRIMARY KEY,
    group_name      VARCHAR(100) NOT NULL,   -- dùng làm "địa chỉ" nhận thư nên phải duy nhất
    owner_id        INT NOT NULL,            -- người tạo = chủ nhóm
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_id) REFERENCES account(account_id) ON DELETE CASCADE,
    -- collation utf8mb4_unicode_ci không phân biệt hoa/thường: "Lop A" trùng "lop a"
    UNIQUE KEY uq_group_name (group_name)
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 7. GROUP_MEMBER: quan hệ nhóm <-> thành viên
-- ---------------------------------------------------------
CREATE TABLE group_member (
    group_id        INT NOT NULL,
    account_id      INT NOT NULL,
    joined_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, account_id),
    FOREIGN KEY (group_id) REFERENCES mail_group(group_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =========================================================
-- GHI CHÚ NGHIỆP VỤ (đọc trước khi code DAO/Service)
-- =========================================================
-- 1. Khi REGISTER: tự động insert 3 folder (INBOX, SENT, TRASH) cho account mới.
-- 2. Khi SEND_MAIL:
--      a. Insert 1 dòng vào `mail`.
--      b. Insert 1 dòng `mail_recipient` cho sender:
--         folder = SENT của sender, recipient_type='SENDER', is_read=TRUE.
--      c. Với mỗi email trong to/cc/bcc (nếu là group thì mở rộng ra
--         từng thành viên trong group_member trước khi insert):
--         insert 1 dòng `mail_recipient`: folder = INBOX của người đó,
--         recipient_type tương ứng, is_read=FALSE.
--      d. Insert các `attachment` gắn theo mail_id.
-- 3. GET_MAIL_LIST(folderId) = SELECT ... FROM mail_recipient r
--       JOIN mail m ON r.mail_id = m.mail_id
--       WHERE r.account_id = ? AND r.folder_id = ? AND r.is_deleted = FALSE
--       ORDER BY m.sent_at DESC;
-- 4. DELETE_MAIL = chuyển folder_id của entry sang folder TRASH thuộc cùng account_id
--       (chỉ ảnh hưởng dòng thư của riêng người chuyển; không xóa dữ liệu).
-- 5. DELETE_TRASH_MAIL / EMPTY_TRASH = chỉ xóa dòng mail_recipient trong folder
--       TRASH thuộc account_id đang đăng nhập; không xóa mail/attachment dùng chung.
-- 6. Owner của mail_group không được LEAVE_GROUP, chỉ được DELETE_GROUP.
-- =========================================================
