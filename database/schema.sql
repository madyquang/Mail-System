-- =========================================================
-- MAIL SYSTEM DATABASE SCHEMA (MySQL) — v3.0
-- Cài mới: chạy tệp này. CSDL đã có từ bản cũ: chạy các tệp trong
-- database/migrations theo thứ tự tên (scripts/setup-db.ps1 làm tự động).
-- =========================================================

-- Tạo CSDL mail_system nếu chưa có; bảng mã utf8mb4 (đủ mọi ký tự Unicode), so sánh không phân biệt hoa thường.
CREATE DATABASE IF NOT EXISTS mail_system
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Các câu lệnh sau chạy trên CSDL này.
USE mail_system;

-- ---------------------------------------------------------
-- 1. ACCOUNT: thông tin tài khoản người dùng
-- ---------------------------------------------------------
-- Bảng tài khoản.
CREATE TABLE account (
    -- Khoá chính tự tăng.
    account_id      INT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(150) NOT NULL UNIQUE,   -- địa chỉ mail nội bộ, VD: an@mail.local
    -- Chuỗi băm mật khẩu PBKDF2 dạng pbkdf2_sha256$<số vòng>$<salt>$<hash> (không lưu mật khẩu gốc).
    password_hash   VARCHAR(255) NOT NULL,
    -- Tên hiển thị; thời điểm tạo tài khoản.
    display_name    VARCHAR(100) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    share_read_receipts BOOLEAN NOT NULL DEFAULT TRUE,  -- cho người gửi biết mình đã đọc thư
    totp_secret     VARCHAR(64) NULL,                -- khoá TOTP (Base32), NULL = chưa thiết lập 2FA
    -- Đã bật xác minh 2 bước chưa.
    totp_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    totp_last_step  BIGINT NULL                      -- bước 30 giây đã dùng gần nhất (chống dùng lại mã)
-- InnoDB: hỗ trợ giao dịch và khoá ngoại.
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 2. FOLDER: mỗi user có bộ thư mục riêng
--    Khi REGISTER thành công -> tự tạo 3 folder mặc định:
--    INBOX, SENT, TRASH. User có thể tạo thêm CUSTOM.
-- ---------------------------------------------------------
-- Bảng thư mục.
CREATE TABLE folder (
    -- Khoá chính; tài khoản sở hữu; tên thư mục; loại (mặc định CUSTOM); thời điểm tạo.
    folder_id       INT AUTO_INCREMENT PRIMARY KEY,
    account_id      INT NOT NULL,
    folder_name     VARCHAR(100) NOT NULL,
    folder_type     ENUM('INBOX','SENT','TRASH','CUSTOM') NOT NULL DEFAULT 'CUSTOM',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Xoá tài khoản thì xoá luôn thư mục của nó.
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    -- Trong một tài khoản, tên thư mục không trùng.
    UNIQUE KEY uq_account_foldername (account_id, folder_name)
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 3. MAIL: nội dung thư — MỘT bản ghi duy nhất cho mỗi thư
--    dù gửi cho bao nhiêu người.
-- ---------------------------------------------------------
-- Bảng nội dung thư.
CREATE TABLE mail (
    -- Khoá chính; người gửi.
    mail_id         INT AUTO_INCREMENT PRIMARY KEY,
    sender_id       INT NOT NULL,
    thread_id       INT NULL,                       -- mail_id của thư đầu hội thoại (NULL = chính nó)
    in_reply_to     INT NULL,                       -- thư được trả lời
    -- Tiêu đề (mặc định "(Không có tiêu đề)").
    subject         VARCHAR(255) NOT NULL DEFAULT '(Không có tiêu đề)',
    body            MEDIUMTEXT,                     -- tối đa 100 000 ký tự (kiểm tra ở Server)
    -- Thời điểm gửi.
    sent_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Xoá tài khoản người gửi thì xoá thư của họ.
    FOREIGN KEY (sender_id) REFERENCES account(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 4. MAIL_RECIPIENT: bảng trung tâm — đại diện cho việc
--    "1 thư xuất hiện trong hộp thư của 1 người, ở 1 folder,
--     với vai trò và trạng thái đọc riêng của người đó".
--    Kể cả người GỬI cũng có 1 dòng ở đây (folder = SENT).
-- ---------------------------------------------------------
-- Bảng "bản thư của từng người".
CREATE TABLE mail_recipient (
    -- Khoá chính (entryId mà Client dùng khi thao tác); thư được tham chiếu.
    entry_id        INT AUTO_INCREMENT PRIMARY KEY,
    mail_id         INT NOT NULL,
    account_id      INT NOT NULL,          -- hộp thư này thuộc về ai
    folder_id       INT NOT NULL,          -- đang nằm ở folder nào
    original_folder_id INT NULL,           -- thư mục trước khi chuyển vào TRASH
    -- Vai trò của người này với thư: Tới / Cc / Bcc / người gửi.
    recipient_type  ENUM('TO','CC','BCC','SENDER') NOT NULL,
    -- Đã đọc chưa (riêng từng người).
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         DATETIME NULL,                  -- lần đầu mở thư (báo đã đọc cho người gửi)
    is_starred      BOOLEAN NOT NULL DEFAULT FALSE,  -- gắn sao: riêng từng người như is_read
    -- Cờ xoá mềm.
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    -- Thời điểm cập nhật dòng (MySQL tự cập nhật khi dòng đổi).
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
    -- Khoá ngoại: xoá thư / tài khoản / thư mục thì xoá dòng; xoá thư mục gốc thì original_folder_id thành NULL.
    FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    FOREIGN KEY (folder_id) REFERENCES folder(folder_id) ON DELETE CASCADE,
    FOREIGN KEY (original_folder_id) REFERENCES folder(folder_id) ON DELETE SET NULL,
    INDEX idx_account_folder (account_id, folder_id),   -- tăng tốc GET_MAIL_LIST
    INDEX idx_mail (mail_id),
    INDEX idx_account_starred (account_id, is_starred)  -- view "Có gắn sao"
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 5. ATTACHMENT: file đính kèm của thư (lưu path, không lưu blob)
-- ---------------------------------------------------------
-- Bảng tệp đính kèm.
CREATE TABLE attachment (
    -- Khoá chính; thư chứa tệp; tên tệp gốc; kiểu MIME; kích thước (byte).
    attachment_id   INT AUTO_INCREMENT PRIMARY KEY,
    mail_id         INT NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    storage_path    VARCHAR(500) NOT NULL,   -- tên tệp trong data/attachments (UUID.đuôi) do Server sinh
    -- Xoá thư thì xoá dòng tệp.
    FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 6. MAIL_GROUP: nhóm mail (mail group)
-- ---------------------------------------------------------
-- Bảng nhóm thư.
CREATE TABLE mail_group (
    -- Khoá chính.
    group_id        INT AUTO_INCREMENT PRIMARY KEY,
    group_name      VARCHAR(100) NOT NULL,   -- gửi bằng tên nhóm vẫn được nên tên phải duy nhất
    group_address   VARCHAR(150) NULL,       -- <ten-nhom>@group.local, sinh từ tên (Server tự điền nếu trống)
    owner_id        INT NOT NULL,            -- người tạo = chủ nhóm
    -- Thời điểm tạo; xoá tài khoản chủ nhóm thì xoá nhóm.
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_id) REFERENCES account(account_id) ON DELETE CASCADE,
    -- collation utf8mb4_unicode_ci không phân biệt hoa/thường: "Lop A" trùng "lop a"
    UNIQUE KEY uq_group_name (group_name),
    -- Địa chỉ nhóm không trùng (nhiều dòng NULL vẫn được phép).
    UNIQUE KEY uq_group_address (group_address)
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 7. GROUP_MEMBER: quan hệ nhóm <-> thành viên
-- ---------------------------------------------------------
-- Bảng thành viên nhóm.
CREATE TABLE group_member (
    -- Nhóm; tài khoản thành viên; thời điểm tham gia.
    group_id        INT NOT NULL,
    account_id      INT NOT NULL,
    joined_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Mỗi người chỉ là thành viên một lần trong một nhóm.
    PRIMARY KEY (group_id, account_id),
    -- Xoá nhóm hoặc tài khoản thì xoá dòng thành viên.
    FOREIGN KEY (group_id) REFERENCES mail_group(group_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 8. DRAFT: thư nháp của từng tài khoản. Người nhận lưu nguyên văn
--    (chưa kiểm tra) vì bản nháp có thể dở dang.
-- ---------------------------------------------------------
-- Bảng thư nháp.
CREATE TABLE draft (
    -- Khoá chính; chủ bản nháp.
    draft_id        INT AUTO_INCREMENT PRIMARY KEY,
    account_id      INT NOT NULL,
    -- Người nhận Tới / Cc / Bcc dạng chữ như người dùng gõ; tiêu đề; nội dung.
    to_text         VARCHAR(2000) NOT NULL DEFAULT '',
    cc_text         VARCHAR(2000) NOT NULL DEFAULT '',
    bcc_text        VARCHAR(2000) NOT NULL DEFAULT '',
    subject         VARCHAR(255) NOT NULL DEFAULT '',
    body            MEDIUMTEXT,
    -- Thời điểm tạo và lần lưu gần nhất (tự cập nhật).
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- Xoá tài khoản thì xoá nháp; chỉ mục để liệt kê nháp mới nhất trước.
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    INDEX idx_draft_account (account_id, updated_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 9. LOGIN_ATTEMPT: bộ đếm đăng nhập sai, lưu bền để khởi động lại
--    Server không xoá khoá chống dò mật khẩu.
-- ---------------------------------------------------------
-- Bảng đếm đăng nhập sai.
CREATE TABLE login_attempt (
    attempt_key     VARCHAR(200) PRIMARY KEY,     -- "email:<email>" hoặc "ip:<địa chỉ>"
    -- Số lần sai liên tiếp; lần sai gần nhất; bị khoá tới lúc nào (NULL = không khoá).
    failures        INT NOT NULL DEFAULT 0,
    last_failure    DATETIME NULL,
    locked_until    DATETIME NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 10. TRUSTED_DEVICE: chứng chỉ thiết bị (mutual TLS) Server đã thấy,
--     và quản trị viên đã phê duyệt hay chưa.
-- ---------------------------------------------------------
-- Bảng thiết bị (chứng chỉ client).
CREATE TABLE trusted_device (
    fingerprint     CHAR(95) PRIMARY KEY,         -- SHA-256 dạng AB:CD:...
    -- Tên trong chứng chỉ; đã được duyệt chưa; lần đầu / lần cuối thấy; IP và tài khoản gần nhất.
    subject         VARCHAR(255) NOT NULL,
    approved        BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_ip         VARCHAR(64) NULL,
    last_account_id INT NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------
-- 11. OUTBOX: thư chờ gửi — hoàn tác được (gửi sau vài giây) hoặc hẹn giờ.
--     Bộ điều phối của Server lấy thư tới hạn ra (SELECT ... FOR UPDATE)
--     và giao trong CÙNG giao dịch, nên một thư không bao giờ gửi hai lần.
-- ---------------------------------------------------------
-- Bảng hộp thư chờ gửi.
CREATE TABLE outbox (
    -- Khoá chính; người gửi.
    outbox_id       INT AUTO_INCREMENT PRIMARY KEY,
    account_id      INT NOT NULL,
    payload         MEDIUMTEXT NOT NULL,          -- JSON: to/cc/bcc (mảng), subject, body, inReplyTo (nếu có)
    -- Thời điểm cần gửi; thời điểm xếp hàng.
    send_at         DATETIME NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Xoá tài khoản thì xoá thư chờ; chỉ mục theo send_at để tìm thư tới hạn nhanh.
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    INDEX idx_outbox_due (send_at)
) ENGINE=InnoDB;

-- Tệp đính kèm của thư đang chờ (tệp đã lưu sẵn trong data/attachments).
CREATE TABLE outbox_attachment (
    -- Khoá chính; thư chờ; tên tệp; kiểu MIME; kích thước; tên tệp lưu trên đĩa.
    outbox_attachment_id INT AUTO_INCREMENT PRIMARY KEY,
    outbox_id       INT NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    storage_path    VARCHAR(500) NOT NULL,
    -- Xoá thư chờ thì xoá dòng tệp.
    FOREIGN KEY (outbox_id) REFERENCES outbox(outbox_id) ON DELETE CASCADE
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
-- 7. "Có gắn sao" và "Thư nháp" là view phía Client, không phải dòng trong bảng folder:
--    gắn sao = mail_recipient.is_starred (trừ TRASH), thư nháp = bảng draft.
-- 8. Hội thoại: thư trả lời có thread_id = thread của thư gốc (chỉ khi người gửi
--    có quyền xem thư gốc). Danh sách thư gộp theo COALESCE(thread_id, mail_id).
-- 9. Thư mục CUSTOM: tối đa 50/tài khoản; xoá thư mục = chuyển thư trong đó vào TRASH.
-- =========================================================
