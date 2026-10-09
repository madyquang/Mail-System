# FEATURES — Chức năng hiện có và ràng buộc

Tài liệu này mô tả các chức năng người dùng có thể sử dụng trong ứng dụng Mail
System hiện tại và các điều kiện được kiểm tra ở giao diện hoặc máy chủ. Đây là
ứng dụng mail nội bộ; email chỉ được gửi giữa các tài khoản đã đăng ký trong
cùng hệ thống, không gửi ra dịch vụ email bên ngoài.

## 1. Đăng ký tài khoản

- Người dùng có thể tạo tài khoản bằng email, tên hiển thị, mật khẩu và xác
  nhận mật khẩu.
- Các trường email, tên hiển thị và mật khẩu không được để trống.
- Email phải theo định dạng cơ bản `ten@tenmien.dom`, không có khoảng trắng; Lưu ý ten mien và .dom có thể đặt bất kì, chưa giới hạn
  email được chuẩn hóa thành chữ thường khi lưu.
- Email không được vượt quá 150 ký tự và phải chưa được tài khoản khác sử dụng.
- Tên hiển thị tối đa 100 ký tự.
- Mật khẩu tối thiểu 6 ký tự; mật khẩu xác nhận phải trùng mật khẩu.
- Khi đăng ký thành công, hệ thống tạo ba thư mục mặc định: Hộp thư đến
  (INBOX), Đã gửi (SENT) và Thùng rác (TRASH). Mật khẩu được lưu dưới dạng hash,
  không lưu dạng văn bản thuần.

## 2. Đăng nhập và đăng xuất

- Phần cuối màn hình đăng nhập có ô **Máy chủ** dạng `host:port` (mặc định
  `localhost:5000`, hoặc theo biến môi trường `MAIL_SERVER_HOST`/
  `MAIL_SERVER_PORT`) để kết nối tới Server trên máy khác trong mạng LAN.
  Ứng dụng chỉ kết nối khi bấm Đăng nhập/Đăng ký, nên vẫn mở được khi Server
  chưa chạy; lỗi kết nối được hiển thị rõ (không tìm thấy máy chủ, quá thời
  gian chờ, máy chủ quá tải).
- Đăng nhập yêu cầu email và mật khẩu không để trống.
- Email và mật khẩu phải khớp với một tài khoản đã đăng ký. Sai thông tin hoặc
  tài khoản không tồn tại đều bị từ chối.
- Đăng nhập thành công sẽ mở hộp thư và tải danh sách thư mục của tài khoản.
- Đăng xuất kết thúc phiên hiện tại và trở về màn hình đăng nhập.
- Có thể đăng nhập cùng một tài khoản trên nhiều cửa sổ/máy cùng lúc.
- Khi mất kết nối tới máy chủ, ứng dụng hiện cảnh báo kèm lý do và quay về màn
  hình đăng nhập; các thao tác đang chờ được báo lỗi thay vì treo.

## 3. Hộp thư và thư mục

- Màn hình chính hiển thị **thẻ hồ sơ** của tài khoản đang đăng nhập: ảnh đại
  diện bằng chữ cái đầu tên, tên hiển thị và email.
- Người dùng có thể xem các thư mục được tạo cho tài khoản: INBOX, SENT và
  TRASH. Thư mục có thư chưa đọc hiện **số thư chưa đọc** (không áp dụng cho
  Đã gửi và Thùng rác), cập nhật ngay khi có thay đổi.
- **Chọn nhiều thư** bằng Ctrl/Shift + click hoặc ô "Chọn tất cả"; thanh thao
  tác hiện số thư đang chọn. Phím Enter mở thư, phím Delete chuyển thư đang
  chọn vào Thùng rác (hoặc xóa vĩnh viễn khi đang ở Thùng rác).
- Danh sách thư hiển thị thư thuộc tài khoản và thư mục đang chọn; trạng thái
  chưa đọc được phân biệt bằng kiểu chữ đậm.
- Có thể mở một thư để xem tiêu đề, người gửi, thời gian, người nhận To/CC/BCC,
  nội dung và các tệp đính kèm. Thư chỉ được xem nếu tài khoản có quyền nhận
  thư đó.
- CC (Carbon Copy) gửi một bản sao cho người được CC. Người nhận chính và những người trong CC đều có thể thấy địa chỉ của nhau.
- BCC chỉ hiển thị cho người gửi và người nhận BCC tương ứng; người nhận khác
  không được xem danh sách BCC.
- Có thể tìm trong thư mục đang mở theo từ khóa tiêu đề/nội dung, theo người
  gửi, hoặc kết hợp cả hai. Điều kiện từ khóa và người gửi mỗi loại tối đa
  255 ký tự; cần nhập ít nhất một điều kiện.
- Nút **Làm mới** tải lại danh sách và áp dụng lại điều kiện tìm/lọc hiện tại.
- Có thể đánh dấu các thư đang chọn là đã đọc. Thao tác chỉ ảnh hưởng trạng
  thái thư của tài khoản hiện tại.
- Có thể chuyển thư đang chọn vào Thùng rác sau khi xác nhận. Thao tác chỉ
  chuyển bản thư của tài khoản hiện tại; thư vẫn được giữ trong cơ sở dữ liệu
  và bản của người nhận khác không bị ảnh hưởng.
- Trong Thùng rác, có thể xóa vĩnh viễn thư đang chọn hoặc dọn sạch toàn bộ
  Thùng rác sau khi xác nhận. Thao tác xóa bản thư của tài khoản hiện tại; nếu
  không còn tài khoản nào tham chiếu thư đó, bản ghi thư và các bản ghi/tệp
  đính kèm của thư cũng được xóa.
- Có thể khôi phục thư đang chọn trong Thùng rác sau khi xác nhận. Thư được
  đưa về đúng thư mục trước khi xóa (INBOX, SENT hoặc thư mục tùy chỉnh);
  trạng thái đọc/chưa đọc được giữ nguyên. Với thư đã chuyển vào Thùng rác
  trước khi có khả năng lưu thư mục nguồn, hệ thống dùng INBOX cho người nhận
  và SENT cho người gửi. Cơ sở dữ liệu đã tồn tại cần áp dụng migration
  `migrations/20261006_restore_original_mail_folder.sql` trước khi nâng cấp
  server.
- Không có thao tác đánh dấu đã đọc trong Thùng rác; mở thư tại đây không làm
  thay đổi trạng thái đã đọc.

## 4. Soạn và gửi thư

- Có thể gửi tới nhiều người nhận trong các trường To, CC và BCC; phân cách
  từng địa chỉ hoặc tên nhóm bằng dấu phẩy.
- Phải có ít nhất một người nhận hợp lệ trong To, CC hoặc BCC.
- Mỗi địa chỉ phải đúng định dạng cơ bản và thuộc về tài khoản đã đăng ký
  trong hệ thống. Nếu có bất kỳ địa chỉ hoặc nhóm không hợp lệ nào, toàn bộ
  thao tác gửi bị từ chối.
- Thư được lưu trong SENT của người gửi và INBOX của những người nhận đã phân
  giải. Một tài khoản chỉ nhận tối đa một bản trong cùng thư, kể cả khi được
  chỉ định lặp lại ở nhiều trường.
- Tiêu đề có thể để trống; khi đó hệ thống dùng “(Không có tiêu đề)”. Tiêu đề
  tối đa 255 ký tự. Nội dung thư có thể để trống.

## 5. Tệp đính kèm

- Có thể đính kèm tối đa 5 tệp cho mỗi thư.
- Tổng kích thước nội dung tệp không vượt quá 25 MiB cho mỗi thư.
- Khi bấm Gửi, các tệp được tải lên máy chủ theo từng khối 512 KB và hiển thị
  tiến độ (đã tải / tổng dung lượng); thư chỉ được gửi sau khi mọi tệp đã tải
  lên đủ. Tệp rỗng hoặc đã bị xoá/thay đổi trong lúc gửi bị báo lỗi.
- Chỉ chấp nhận phần mở rộng PDF, DOCX, PPTX, TXT, JPG, PNG và ZIP; phần mở
  rộng không phân biệt chữ hoa/thường.
- Máy chủ kiểm tra lại số lượng, phần mở rộng, nội dung mã hóa và kích thước
  trước khi lưu; không chỉ dựa vào kiểm tra của client.
- Người có quyền truy cập thư có thể chọn nơi lưu tệp đính kèm. Ứng dụng tải
  theo từng khối, kiểm tra kích thước, giữ phần mở rộng gốc và yêu cầu hệ điều
  hành mở tệp sau khi tải xong. Việc mở được tệp phụ thuộc ứng dụng tương ứng
  đã có trên máy người dùng.

## 6. Quản lý nhóm thư

- Người dùng đã đăng nhập có thể tạo nhóm và xem các nhóm mình là thành viên.
- Tên nhóm không được để trống, tối đa 100 ký tự, không chứa `@` hoặc dấu
  phẩy; tên nhóm đã được sử dụng (không phân biệt hoa/thường) không thể tạo lại. Tên nhóm được dùng làm
  người nhận nên không dùng dấu phẩy.
- Người tạo tự động là chủ nhóm và cũng là thành viên đầu tiên.
- Chỉ chủ nhóm được thêm thành viên, xóa thành viên hoặc xóa nhóm.
- Thành viên được thêm phải có tài khoản trong hệ thống, email đúng định dạng
  và chưa là thành viên của nhóm.
- Chủ nhóm không thể tự xóa mình khỏi nhóm hoặc rời nhóm; chủ nhóm có thể xóa
  nhóm. Thành viên thường có thể rời nhóm.
- Chỉ thành viên hiện tại của một nhóm mới được gửi thư tới nhóm đó. Khi gửi,
  máy chủ phân giải tên nhóm thành các thành viên **trừ chính người gửi**;
  người gửi không phải thành viên, nhóm không tồn tại, hoặc nhóm không có ai
  khác ngoài người gửi sẽ bị từ chối.
- Khi xóa nhóm, quan hệ thành viên của nhóm cũng bị xóa. Hiện chưa có chức
  năng đổi tên nhóm, chuyển quyền chủ nhóm hoặc khôi phục nhóm đã xóa.

## 7. Cập nhật trạng thái và giới hạn sử dụng

- Thư mới, trạng thái đã đọc, xóa và khôi phục thư có phát sự kiện tới mọi
  phiên đang đăng nhập của tài khoản để hộp thư cập nhật ngay. Nếu client mất
  kết nối, người dùng đăng nhập lại để tải trạng thái mới nhất.
- Ứng dụng tự gửi tín hiệu kiểm tra kết nối mỗi 30 giây; máy chủ đóng kết nối
  không hoạt động quá 5 phút và giới hạn số kết nối đồng thời (mặc định 100).
- Cần có máy chủ Mail System đang chạy và kết nối được với MySQL. Máy khách
  kết nối máy chủ TCP theo cấu hình của ứng dụng.
- Hệ thống hiện không cung cấp gửi/nhận qua Internet bằng SMTP/IMAP, tạo thư
  mục tùy chỉnh từ giao diện, hoặc tìm kiếm đồng thời trên tất cả thư mục.
