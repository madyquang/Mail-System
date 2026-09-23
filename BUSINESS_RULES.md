# BUSINESS RULES — Mail System

> File này tổng hợp TOÀN BỘ quy tắc nghiệp vụ của hệ thống, tách riêng khỏi chi
> tiết kỹ thuật (Protocol xem ở PROTOCOL.md, Schema xem ở schema.sql).
>
> **Cách dùng**: khi nhờ AI viết code cho 1 phần cụ thể (VD: AuthService,
> MailService...), hãy đính kèm file này + file interface/class rỗng tương ứng
> + schema.sql (nếu là DAO) để AI hiểu đúng nghiệp vụ, không tự đoán/bịa ra quy
> tắc khác.

## 1. Tổng quan

Hệ thống mail nội bộ Client-Server: 1 Server trung tâm quản lý toàn bộ tài
khoản, hộp thư, nội dung thư, trạng thái đọc/chưa đọc. Nhiều Client (JavaFX)
kết nối đồng thời qua TCP Socket, giao tiếp bằng giao thức JSON tự thiết kế
(không dùng SMTP/IMAP, không dùng REST API).

## 2. Tài khoản & Đăng nhập

- Mỗi tài khoản gắn với **1 địa chỉ email nội bộ duy nhất** trong hệ thống
  (không phải email thật ngoài đời, VD: `an@mail.local`).
- Đăng ký (REGISTER): cần email (duy nhất, không trùng), password, tên hiển thị.
  Password PHẢI được hash trước khi lưu (không lưu plain text).
- Đăng ký thành công → tự động tạo sẵn 3 folder cho tài khoản: **Hộp thư đến
  (INBOX)**, **Đã gửi (SENT)**, **Thùng rác (TRASH)**.
- Đăng nhập (LOGIN): kiểm tra email tồn tại + password khớp. Sai 1 trong 2 →
  trả lỗi chung chung "Email hoặc mật khẩu không đúng" (KHÔNG nói rõ email hay
  password sai — tránh lộ thông tin email nào đã đăng ký, đây là thông lệ bảo
  mật cơ bản).

## 3. Hộp thư & Thư mục

- Người dùng có nhiều thư mục: Hộp thư đến, Đã gửi, và có thể tạo thêm thư mục
  tùy chỉnh (CUSTOM).
- Danh sách thư trong 1 thư mục: hiển thị người gửi, tiêu đề, thời gian nhận,
  trạng thái đã đọc/chưa đọc. **Thư chưa đọc phải in đậm** để phân biệt.
- Mở 1 thư ra xem chi tiết → **tự động đánh dấu đã đọc** ngay lúc đó (không cần
  thao tác đánh dấu riêng).
- Trạng thái đã đọc/chưa đọc và việc xóa thư là **riêng theo từng người nhận**:
  A đọc thư của mình không làm thư đó hiện "đã đọc" ở hộp thư của B (dù cùng
  nhận 1 thư gửi chung).
- Xóa thư: là "soft delete" (đánh dấu ẩn đi), không xóa hẳn dữ liệu khỏi CSDL.

## 4. Soạn & Gửi thư

- Người nhận có thể ở 3 vai trò: **To** (chính), **CC**, **BCC** — có thể gửi
  tới nhiều người cùng lúc, ở cả 3 trường.
- **Server phải kiểm tra người nhận tồn tại trong hệ thống trước khi lưu.**
  Nếu BẤT KỲ người nhận nào (dù ở To/CC/BCC) không tồn tại → **toàn bộ thư gửi
  thất bại**, không lưu một phần nào cả (all-or-nothing), trả lỗi rõ ràng cho
  Client biết người nhận nào không hợp lệ.
- Nếu người nhận là 1 **Mail Group** (thay vì email cá nhân): Server tự động mở
  rộng thành danh sách từng thành viên trong group đó trước khi lưu — mỗi
  thành viên nhận 1 bản riêng, giống như được liệt kê tên trực tiếp.
- Sau khi gửi thành công: thư xuất hiện trong **Đã gửi** của người gửi, và
  trong **Hộp thư đến** của từng người nhận (không tính người ở BCC hiện ra với
  người khác — BCC chỉ người gửi và chính người đó biết mình có trong BCC).

## 5. Đính kèm file

- Tối đa **5 file / thư**, tổng dung lượng tối đa **25MB / thư**.
- Định dạng cho phép: PDF, DOCX, PPTX, TXT, JPG, PNG, ZIP. File định dạng khác
  → từ chối, báo lỗi rõ ràng.
- **Server luôn phải tự kiểm tra lại** số lượng/dung lượng/định dạng file dù
  Client đã kiểm tra trước đó (không tin tưởng tuyệt đối dữ liệu từ Client).
- Người nhận có thể xem thông tin và tải xuống file đính kèm.

## 6. Đồng bộ real-time (không cần refresh)

- Khi có **thư mới đến**, thư bị **đánh dấu đã đọc**, hoặc **bị xóa** từ 1
  thiết bị/phiên đăng nhập khác, mọi Client khác đang đăng nhập cùng tài khoản
  đó và đang mở đúng thư mục liên quan phải **tự động cập nhật giao diện ngay
  lập tức**, không cần người dùng bấm refresh.
- Đây là do Server **chủ động đẩy** thông báo (không phải Client tự hỏi lại
  liên tục — không dùng polling).

## 7. Mail Group

- Bất kỳ user nào cũng có thể tạo 1 group mail (không cần quyền Admin hệ thống).
- Người tạo group tự động là **Owner (chủ nhóm)**.
- **Chỉ Owner** được: xóa nhóm, thêm thành viên, xóa thành viên.
- Thành viên thường (không phải Owner): có thể gửi mail tới group, và có thể
  **rời nhóm** (LEAVE_GROUP).
- **Owner KHÔNG được rời nhóm** (vì sẽ làm nhóm mất chủ) — Owner muốn không
  còn liên quan tới nhóm thì phải xóa hẳn nhóm (DELETE_GROUP).
- Không có khái niệm Admin toàn hệ thống trong phạm vi đồ án này.

## 8. Tìm kiếm & Lọc

- Tìm theo từ khóa xuất hiện trong **tiêu đề hoặc nội dung** thư.
- Có thể lọc thêm theo người gửi.
- Phạm vi tìm kiếm mặc định: trong thư mục đang mở (có thể mở rộng tìm toàn bộ
  nếu người dùng chọn, tùy độ hoàn thiện UI mà TV3 quyết định).

## 9. Những điều CẦN đảm bảo xuyên suốt (áp dụng mọi chức năng)

- Mọi lỗi nghiệp vụ (người nhận sai, quyền không đủ, file vượt giới hạn...)
  phải trả về thông báo lỗi rõ ràng cho Client, **không được làm crash** kết
  nối hay để Client treo không phản hồi.
- Không bao giờ trả `password_hash` hay bất kỳ thông tin nhạy cảm nào khác về
  phía Client.
- Một hành động của user A không được làm sai lệch dữ liệu/trạng thái của user
  B (trừ trường hợp cố ý như "xóa nhóm" ảnh hưởng tất cả thành viên).
