-- Lưu thư mục gốc khi chuyển thư vào Thùng rác (để khôi phục đúng chỗ).
-- Chạy lại nhiều lần vẫn an toàn: chỉ thêm cột/khoá ngoại khi chưa có.
USE mail_system;

-- Đếm xem cột original_folder_id đã có chưa (tra information_schema).
SET @has_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_recipient' AND COLUMN_NAME = 'original_folder_id');
-- Chưa có: câu ALTER thêm cột; có rồi: câu SELECT in thông báo bỏ qua.
SET @sql := IF(@has_column = 0,
    'ALTER TABLE mail_recipient ADD COLUMN original_folder_id INT NULL AFTER folder_id',
    'SELECT ''original_folder_id: da co, bo qua'' AS info');
-- Chuẩn bị, chạy, rồi giải phóng câu lệnh động vừa chọn.
PREPARE statement FROM @sql; EXECUTE statement; DEALLOCATE PREPARE statement;

-- Đếm xem khoá ngoại original_folder_id -> folder đã có chưa.
SET @has_fk := (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_recipient' AND COLUMN_NAME = 'original_folder_id'
      AND REFERENCED_TABLE_NAME = 'folder');
-- Chưa có thì thêm khoá ngoại (xoá thư mục gốc thì cột thành NULL).
SET @sql := IF(@has_fk = 0,
    'ALTER TABLE mail_recipient ADD CONSTRAINT fk_mail_recipient_original_folder FOREIGN KEY (original_folder_id) REFERENCES folder(folder_id) ON DELETE SET NULL',
    'SELECT ''khoa ngoai original_folder_id: da co, bo qua'' AS info');
-- Chạy câu lệnh động.
PREPARE statement FROM @sql; EXECUTE statement; DEALLOCATE PREPARE statement;
