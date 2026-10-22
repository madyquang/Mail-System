-- Lưu thư mục gốc khi chuyển thư vào Thùng rác (để khôi phục đúng chỗ).
-- Chạy lại nhiều lần vẫn an toàn: chỉ thêm cột/khoá ngoại khi chưa có.
USE mail_system;

SET @has_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_recipient' AND COLUMN_NAME = 'original_folder_id');
SET @sql := IF(@has_column = 0,
    'ALTER TABLE mail_recipient ADD COLUMN original_folder_id INT NULL AFTER folder_id',
    'SELECT ''original_folder_id: da co, bo qua'' AS info');
PREPARE statement FROM @sql; EXECUTE statement; DEALLOCATE PREPARE statement;

SET @has_fk := (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_recipient' AND COLUMN_NAME = 'original_folder_id'
      AND REFERENCED_TABLE_NAME = 'folder');
SET @sql := IF(@has_fk = 0,
    'ALTER TABLE mail_recipient ADD CONSTRAINT fk_mail_recipient_original_folder FOREIGN KEY (original_folder_id) REFERENCES folder(folder_id) ON DELETE SET NULL',
    'SELECT ''khoa ngoai original_folder_id: da co, bo qua'' AS info');
PREPARE statement FROM @sql; EXECUTE statement; DEALLOCATE PREPARE statement;
