-- v2.0: thư gắn sao, thư nháp, nội dung thư dài hơn, đường dẫn tệp tương đối.
-- Chạy lại nhiều lần vẫn an toàn.
USE mail_system;

-- 1. Gắn sao: cờ riêng cho từng "bản thư của tôi" (giống is_read).
-- Đếm xem cột is_starred đã có chưa.
SET @has_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_recipient' AND COLUMN_NAME = 'is_starred');
-- Chưa có: thêm cột is_starred sau is_read và chỉ mục (account_id, is_starred).
SET @sql := IF(@has_column = 0,
    'ALTER TABLE mail_recipient ADD COLUMN is_starred BOOLEAN NOT NULL DEFAULT FALSE AFTER is_read, ADD INDEX idx_account_starred (account_id, is_starred)',
    'SELECT ''is_starred: da co, bo qua'' AS info');
-- Chạy câu lệnh động.
PREPARE statement FROM @sql; EXECUTE statement; DEALLOCATE PREPARE statement;

-- 2. Nội dung thư tối đa 100 000 ký tự: TEXT chỉ chứa 65 535 byte (~21 000 ký tự tiếng Việt 3 byte).
-- Đổi kiểu cột body sang MEDIUMTEXT (tối đa 16 MB); chạy lại vẫn an toàn.
ALTER TABLE mail MODIFY body MEDIUMTEXT;

-- 3. Thư nháp: người nhận lưu nguyên văn (chưa kiểm tra) vì bản nháp có thể dở dang.
-- Tạo bảng draft nếu chưa có (cùng định nghĩa với schema.sql).
CREATE TABLE IF NOT EXISTS draft (
    draft_id        INT AUTO_INCREMENT PRIMARY KEY,
    account_id      INT NOT NULL,
    to_text         VARCHAR(2000) NOT NULL DEFAULT '',
    cc_text         VARCHAR(2000) NOT NULL DEFAULT '',
    bcc_text        VARCHAR(2000) NOT NULL DEFAULT '',
    subject         VARCHAR(255) NOT NULL DEFAULT '',
    body            MEDIUMTEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES account(account_id) ON DELETE CASCADE,
    INDEX idx_draft_account (account_id, updated_at)
) ENGINE=InnoDB;

-- 4. storage_path chỉ còn là TÊN tệp trong data/attachments (trước đây là đường dẫn tuyệt đối).
--    Bản ghi cũ vẫn đọc được; chuyển tệp cũ vào data/attachments rồi chạy câu dưới để rút gọn:
--    UPDATE attachment SET storage_path = SUBSTRING_INDEX(REPLACE(storage_path, '\', '/'), '/', -1)
--     WHERE storage_path LIKE '%/%' OR storage_path LIKE '%\\%';
