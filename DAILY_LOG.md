## [1.0.3] - 2026-10-08

Tập trung vào tầng mạng (độ bền, đồng thời, truyền tệp) và tài liệu kiến thức lập trình mạng.

- Framing: thay `readLine()` bằng `FrameReader` giới hạn 1 MiB ký tự/frame; frame quá lớn bị bỏ qua và luồng tự đồng bộ lại thay vì làm Server hết bộ nhớ. Thêm `ProtocolConstants` dùng chung hai phía.
- Server: thread pool + giới hạn `server.maxClients` kết nối (kết nối vượt mức nhận frame lỗi "quá tải" rồi đóng); `TCP_NODELAY`, keep-alive, idle timeout `SO_TIMEOUT`; tắt Server gọn gàng bằng shutdown hook.
- `ClientHandler`: tách thread ghi với hàng đợi có giới hạn, nên EVENT gửi từ thread khác không bao giờ chặn và frame không trộn byte; client không đọc kịp bị ngắt; kiểm tra đăng nhập tập trung; trả lỗi rõ ràng cho JSON hỏng/command lạ mà không đóng kết nối.
- Sửa lỗi đa thiết bị: `SessionManager` lưu tập phiên theo tài khoản; một thiết bị thoát không còn xoá đăng ký của thiết bị khác.
- Client: kết nối khi đăng nhập (không crash khi Server chưa bật), ô nhập máy chủ `host:port` để chạy qua LAN, connect timeout 5 s, timeout 30 s cho mọi request, heartbeat `PING` 30 s, mất kết nối thì mọi request đang chờ báo lỗi ngay và quay về màn hình đăng nhập; frame hỏng không còn làm chết thread nghe.
- Tệp đính kèm: upload theo khối 512 KB (`UPLOAD_ATTACHMENT_CHUNK`) có tiến độ; `SEND_MAIL` chỉ gửi `uploadId`; Server kiểm tra lại và chuyển tệp vào kho trong transaction; tệp tạm bị dọn khi đăng xuất/mất kết nối/khởi động lại.
- Cấu hình Server tập trung (`ServerConfig`), cho phép ghi đè bằng biến môi trường (VD `MAIL_DB_PASSWORD`); timeout kết nối MySQL.
- CSDL: tên nhóm `UNIQUE` (migration `20261008_unique_group_name.sql`), Service bắt vi phạm ràng buộc khi tạo nhóm/thêm thành viên đồng thời.
- `ManualTestClient` in mọi frame nhận được kể cả EVENT; thêm command `PING`.
- Thêm 23 test tự động không cần MySQL (framing, TCP thật, phiên, upload, mất kết nối phía Client); build dùng `maven.compiler.release=17`.
- Tài liệu: `docs/NETWORK_PROGRAMMING.md`, `docs/PROTOCOL.md`, viết lại README.
- Kiểm tra: `mvn install` thành công, 23/23 test đạt (chạy lặp 3 lần). Chưa chạy thử end-to-end với MySQL thật trong lần cập nhật này.

---

## [1.0.2] - 2026-10-06

- Triển khai gửi thư: kiểm tra người nhận email/nhóm, xác thực tệp đính kèm và lưu mail cùng các recipient theo transaction.
- Gửi tới To/CC/BCC và group; lưu thư vào Sent/Inbox, phát event NEW_MAIL cho phiên đang online.
- Triển khai tìm kiếm theo tiêu đề/nội dung, giới hạn theo tài khoản và folder.
- Triển khai tải danh sách/chi tiết thư; mở thư tự đánh dấu đã đọc, BCC được giới hạn theo quyền xem.
- Cho phép người nhận tải tệp đính kèm theo từng khối, xác minh kích thước, giữ đúng phần mở rộng gốc khi lưu và tự động mở bằng ứng dụng mặc định.
- Triển khai command MARK_READ và thao tác đánh dấu thư đã chọn là đã đọc; lưu trạng thái theo tài khoản và phát event MAIL_READ_UPDATED để đồng bộ phiên đang online.
- Triển khai command MARK_READ và nút đánh dấu thư đã chọn là đã đọc; trạng thái được lưu riêng theo tài khoản và phát event MAIL_READ_UPDATED để đồng bộ phiên đang online.
- Hoàn thiện bộ lọc thư theo người gửi (email/tên hiển thị), kết hợp tìm trong tiêu đề/nội dung; làm mới áp dụng lại điều kiện lọc hiện tại.
- Triển khai mail group: tạo nhóm với owner mặc định, tải nhóm/thành viên, thêm/xóa thành viên, rời nhóm và xóa nhóm theo quyền; chỉ thành viên nhóm mới được gửi thư tới nhóm.
- Khắc phục lỗi quản lý nhóm do Server/Client chạy với `mail-common` snapshot cũ: yêu cầu cài lại toàn bộ Maven reactor trước khi chạy module riêng; tối ưu tải nhóm/thành viên bằng một truy vấn JOIN.
- Kiểm thử luồng nhóm qua TCP và MySQL: tạo/liệt kê nhóm, thêm/xóa thành viên, rời/xóa nhóm và từ chối quyền xóa của thành viên thường; dữ liệu test đã được dọn.
- Ban đầu triển khai xóa thư dạng soft delete theo từng tài khoản, kiểm tra thư thuộc hộp thư người dùng và phát event MAIL_DELETED để đồng bộ phiên đang online; luồng này đã được thay bằng chuyển thư vào Thùng rác như ghi nhận bên dưới.
- Cập nhật thao tác xóa thư: chuyển bản thư của tài khoản hiện tại sang thư mục TRASH thay vì ẩn bằng cờ xóa; thư vẫn được giữ lại và danh sách Thùng rác hiển thị thư đã chuyển. Nút chuyển vào Thùng rác bị khóa khi đang xem Thùng rác.
- Thêm thao tác xóa vĩnh viễn thư đang chọn và dọn sạch toàn bộ Thùng rác; máy chủ chỉ xóa các dòng recipient thuộc TRASH của tài khoản đang đăng nhập, dọn bản ghi mail/attachment và tệp đính kèm orphan, đồng thời giữ nguyên dữ liệu còn được hộp thư khác tham chiếu.
- Sửa Thùng rác: ẩn thao tác đánh dấu đã đọc và bảo đảm mở thư trong TRASH không cập nhật trạng thái đọc; thêm log lỗi database cho hai thao tác xóa vĩnh viễn để hiển thị nguyên nhân khi phát sinh sự cố.
- Thêm chức năng khôi phục thư đã chọn từ Thùng rác về đúng thư mục nguồn (kể cả CUSTOM), giữ nguyên trạng thái đọc và phát event để đồng bộ các phiên khác; lưu folder nguồn khi chuyển vào TRASH và có fallback INBOX/SENT cho thư cũ.
- Kiểm tra: `mvn clean install` thành công; luồng mail group đã được smoke-test với MySQL cục bộ.
- Kiểm tra bổ sung: `mvn -q clean test` thành công.

---

## [1.0.1] - 2026-10-05

- Hoàn thiện các màn hình: Đăng nhập, Đăng ký, Inbox, Soạn thư, Xem chi tiết thư.
- Triển khai đăng ký: kiểm tra email trùng, hash mật khẩu PBKDF2 và tạo ba folder mặc định trong transaction.
- Validate phía client:
  - Kiểm tra trường bắt buộc
  - định dạng email : email phải có ít nhất một ký tự trước @, tên miền có ít nhất một ký tự, có dấu chấm . trong tên miền, và có ký tự sau dấu chấm. Email không được chứa khoảng trắng, chưa ràng buộc đôi email
  - mật khẩu $\ge 6$ ký tự, khớp xác nhận mật khẩu.
- Triển khai đăng nhập: xác minh mật khẩu, tạo session và chuyển client vào inbox; bổ sung tải danh sách folder.

---

## Còn cần hoàn thiện

- Sửa lại giao diện
- Mã hoá đường truyền bằng TLS (hiện mật khẩu và nội dung thư đi dạng văn bản rõ)
- Giới hạn số lần đăng nhập sai

---
