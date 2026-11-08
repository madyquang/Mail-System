-- Địa chỉ nhóm thư dạng <ten-nhom>@group.local (duy nhất). Nhóm đã có được Server
-- tự điền địa chỉ lúc khởi động (sinh từ tên nhóm, bỏ dấu tiếng Việt).
-- Chạy lại nhiều lần vẫn an toàn.
USE mail_system;

-- Đếm xem cột group_address đã có chưa.
SET @has_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_group' AND COLUMN_NAME = 'group_address');
-- Chưa có thì thêm cột sau group_name; có rồi thì SELECT 1 (không làm gì).
SET @ddl = IF(@has_column = 0,
    'ALTER TABLE mail_group ADD COLUMN group_address VARCHAR(150) NULL AFTER group_name',
    'SELECT 1');
-- Chạy câu lệnh động.
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- Đếm xem chỉ mục uq_group_address đã có chưa.
SET @has_index = (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_group' AND INDEX_NAME = 'uq_group_address');
-- Chưa có thì thêm chỉ mục UNIQUE cho group_address.
SET @ddl = IF(@has_index = 0,
    'ALTER TABLE mail_group ADD UNIQUE KEY uq_group_address (group_address)',
    'SELECT 1');
-- Chạy câu lệnh động.
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;
