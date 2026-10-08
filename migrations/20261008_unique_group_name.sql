-- Apply once to an existing Mail System database (after 20261006_restore_original_mail_folder.sql).
-- Tên nhóm được dùng làm người nhận thư nên phải duy nhất. Trước đây chỉ Server
-- kiểm tra (SELECT rồi INSERT), hai request đồng thời vẫn có thể tạo trùng tên.
--
-- Nếu câu lệnh báo lỗi "Duplicate entry", hãy tìm và đổi tên/xoá nhóm trùng trước:
--   SELECT group_name, COUNT(*) FROM mail_group GROUP BY group_name HAVING COUNT(*) > 1;
USE mail_system;

ALTER TABLE mail_group
    ADD CONSTRAINT uq_group_name UNIQUE (group_name);
