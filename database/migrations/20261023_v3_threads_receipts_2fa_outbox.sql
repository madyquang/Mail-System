-- v3.0: hội thoại, báo đã đọc, xác thực 2 lớp, khoá đăng nhập lưu bền,
-- thiết bị tin cậy (mutual TLS), hộp thư chờ gửi (hoàn tác / hẹn giờ gửi).
-- Chạy lại nhiều lần vẫn an toàn.
USE mail_system;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table, ' ADD COLUMN ', p_column, ' ', p_definition);
        PREPARE statement FROM @ddl;
        EXECUTE statement;
        DEALLOCATE PREPARE statement;
    END IF;
END //
DELIMITER ;

-- 1. Hội thoại: thư trả lời trỏ về thư gốc; thread_id = mail_id của thư đầu luồng.
CALL add_column_if_missing('mail', 'thread_id', 'INT NULL AFTER sender_id');
CALL add_column_if_missing('mail', 'in_reply_to', 'INT NULL AFTER thread_id');

-- 2. Báo đã đọc: thời điểm người nhận mở thư lần đầu; người nhận có thể tắt chia sẻ.
CALL add_column_if_missing('mail_recipient', 'read_at', 'DATETIME NULL AFTER is_read');
CALL add_column_if_missing('account', 'share_read_receipts', 'BOOLEAN NOT NULL DEFAULT TRUE');

-- 3. Xác thực 2 lớp (TOTP, RFC 6238). totp_last_step chống dùng lại một mã.
CALL add_column_if_missing('account', 'totp_secret', 'VARCHAR(64) NULL');
CALL add_column_if_missing('account', 'totp_enabled', 'BOOLEAN NOT NULL DEFAULT FALSE');
CALL add_column_if_missing('account', 'totp_last_step', 'BIGINT NULL');

DROP PROCEDURE add_column_if_missing;

-- 4. Khoá đăng nhập lưu bền: khởi động lại Server không xoá bộ đếm.
CREATE TABLE IF NOT EXISTS login_attempt (
    attempt_key     VARCHAR(200) PRIMARY KEY,     -- "email:<email>" hoặc "ip:<địa chỉ>"
    failures        INT NOT NULL DEFAULT 0,
    last_failure    DATETIME NULL,
    locked_until    DATETIME NULL
) ENGINE=InnoDB;

-- 5. Thiết bị (chứng chỉ client trong mutual TLS) và trạng thái phê duyệt.
CREATE TABLE IF NOT EXISTS trusted_device (
    fingerprint     CHAR(95) PRIMARY KEY,         -- SHA-256 dạng AB:CD:...
    subject         VARCHAR(255) NOT NULL,
    approved        BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_ip         VARCHAR(64) NULL,
    last_account_id INT NULL
) ENGINE=InnoDB;

-- 6. Hộp thư chờ gửi: thư hoàn tác được (gửi sau vài giây) hoặc hẹn giờ.
CREATE TABLE IF NOT EXISTS outbox (
    outbox_id       INT AUTO_INCREMENT PRIMARY KEY,
    account_id      INT NOT NULL,
    payload         MEDIUMTEXT NOT NULL,          -- JSON: to/cc/bcc/subject/body/forwardAttachmentIds/inReplyTo
    send_at         DATETIME NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    INDEX idx_outbox_due (send_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_attachment (
    outbox_attachment_id INT AUTO_INCREMENT PRIMARY KEY,
    outbox_id       INT NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    storage_path    VARCHAR(500) NOT NULL,
    FOREIGN KEY (outbox_id) REFERENCES outbox(outbox_id) ON DELETE CASCADE
) ENGINE=InnoDB;
