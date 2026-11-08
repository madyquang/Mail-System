-- Tên nhóm là duy nhất (được dùng làm "địa chỉ" người nhận).
-- Chạy lại nhiều lần vẫn an toàn. Nếu báo "Duplicate entry", hãy đổi tên/xoá nhóm trùng trước:
--   SELECT group_name, COUNT(*) FROM mail_group GROUP BY group_name HAVING COUNT(*) > 1;
USE mail_system;

-- Đếm xem chỉ mục uq_group_name đã có chưa.
SET @has_index := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mail_group' AND INDEX_NAME = 'uq_group_name');
-- Chưa có thì thêm ràng buộc UNIQUE cho group_name; có rồi thì in thông báo.
SET @sql := IF(@has_index = 0,
    'ALTER TABLE mail_group ADD CONSTRAINT uq_group_name UNIQUE (group_name)',
    'SELECT ''uq_group_name: da co, bo qua'' AS info');
-- Chạy câu lệnh động.
PREPARE statement FROM @sql; EXECUTE statement; DEALLOCATE PREPARE statement;
