# Changelog

## [Unreleased]

- Nâng yêu cầu Java lên JDK 25 LTS, cập nhật CI và đóng gói; dùng API virtual thread trực tiếp.
- Màn hình đăng nhập: không kết nối được thì tự mở ô Máy chủ, chọn sẵn địa chỉ đang nhớ để sửa; màn đăng ký chỉ chỗ đổi máy chủ.
- Thông báo nhóm: người được thêm vào / bị xoá khỏi nhóm, hoặc nhóm bị xoá, nhận thư thông báo trong Hộp thư đến
  (kể cả khi offline) và EVENT `GROUP_UPDATED`; chủ nhóm được báo khi có người tự rời; cửa sổ Nhóm thư tự cập nhật.
- Sửa lỗi cửa sổ Nhóm thư: khi phiên kết thúc (đăng xuất, Server khởi động lại, bị thu hồi) cửa sổ Nhóm thư / Tài khoản
  của phiên cũ được đóng; trước đây chúng còn mở và thao tác chạy dưới phiên mới (có thể là tài khoản khác).
  Bấm Nhóm thư khi cửa sổ đã mở thì đưa cửa sổ lên trước thay vì mở thêm. Khung soạn thư đang có nội dung được giữ lại
  nhưng chỉ gửi / lưu nháp được dưới đúng tài khoản đã mở nó.
- Nút đỏ (Xoá nhóm, Xoá vĩnh viễn...): icon trắng dễ đọc; giao diện tối dùng chữ tối trên nền hồng.
- Gợi ý khi gõ chỉ hiện người đã trao đổi thư với mình (không còn dò được email của mọi tài khoản).
- Bỏ hoàn tác gửi: bấm Gửi là thư đi ngay (Server vẫn hỗ trợ `delaySeconds`; hẹn giờ gửi giữ nguyên).

## [3.0.0] - 2026-10-22

**Mạng & hiệu năng**
- Tự kết nối lại khi rớt mạng: exponential backoff (1 → 30 giây), `RESUME_SESSION` với token một-lần xoay vòng
  (Server chỉ lưu SHA-256), request chỉ-đọc được gửi lại tự động sau khi nối lại; dải thông báo trạng thái.
- Truyền tiếp tệp: phiên tải lên gắn với tài khoản (giữ 30 phút), `UPLOAD_STATUS`; tải xuống ghi `.mailpart` theo offset.
- Virtual thread (Java 21+, gọi qua reflection, vẫn biên dịch Java 17) và HikariCP cho MySQL.
- Bảng giám sát HTTP (`com.sun.net.httpserver`, chỉ 127.0.0.1, mã truy cập): kết nối, request/giây, độ trễ,
  pool MySQL, thiết bị, nhật ký bảo mật; ngắt kết nối và duyệt thiết bị từ trình duyệt.
- Kiểm thử tải `LoadTest` (100 client: ~2 760 request/giây, p99 15,6 ms, 0 lỗi).

**Bảo mật**
- Xác minh 2 bước TOTP (RFC 6238) có mã QR (ZXing), chống dùng lại mã.
- Mutual TLS: chứng chỉ thiết bị EC P-256 tự tạo, Server ghi nhận / duyệt (`tls.clientAuth` off|want|need).
- Khoá đăng nhập lưu bền trong bảng `login_attempt`.
- Kết nối MySQL qua TLS (`sslMode=PREFERRED` mặc định), Server in bộ mã hoá đang dùng.
- Mã lỗi máy đọc được trong RESPONSE (`code`: TOTP_REQUIRED, RESUME_REJECTED, DEVICE_NOT_APPROVED, RATE_LIMITED).

**Tính năng**
- Thư mục tự tạo (tạo / đổi tên / xoá), chuyển thư bằng kéo thả hoặc menu "Chuyển tới".
- Hội thoại: `inReplyTo`/`threadId`, danh sách gộp theo hội thoại, khung đọc dạng chuỗi thư.
- Hoàn tác gửi (8 giây) và hẹn giờ gửi qua hộp thư chờ `outbox`; giao thư trong cùng giao dịch với việc
  lấy khỏi hàng chờ nên không gửi trùng; mục "Đã lên lịch".
- Báo đã đọc (`READ_RECEIPT`, danh sách người đã xem), có tuỳ chọn tắt.
- Giao diện tối (token màu trong `dark.css`), xem trước ảnh / PDF (PDFBox) / TXT, kéo thả tệp vào thư.

**Hạ tầng**
- Migration `20261023_v3_threads_receipts_2fa_outbox.sql`; `schema.sql` cập nhật tương ứng.
- GitHub Actions: build + test JDK 17/21, e2e với MySQL 8.
- `scripts/package.ps1` (jpackage, kèm runtime, giữ `keytool`), `scripts/loadtest.ps1`.
- Test: 54 unit test (6 common, 41 server, 7 client), 109 kịch bản e2e.

## [2.1.0] - 2026-10-22

- Gợi ý khi gõ (autocomplete) dùng chung một component `AutoComplete`: ô Tới/Cc/Bcc gợi ý người và nhóm (người đã trao đổi thư xếp trước), ô tìm thư gợi ý thư khớp, người gửi và tìm kiếm gần đây, ô Người gửi và ô thêm thành viên nhóm gợi ý người. Debounce 180 ms, bỏ phản hồi cũ về trễ, điều hướng bằng bàn phím.
- Lệnh mới `SUGGEST_CONTACTS` (`ContactService`, `JdbcContactDAO`); tìm kiếm từ gợi ý chạy trên mọi thư mục và tự mở thư được chọn.
- Sửa lỗi giờ hiển thị lệch +7 tiếng (đọc DATETIME bằng `LocalDateTime` thay vì `Timestamp` + `serverTimezone=UTC`).
- Thêm `ContactServiceTest` (tổng 47 test) và 5 kịch bản gợi ý trong `EndToEndCheck` (tổng 56).

## [2.0.0] - 2026-10-22

Tái cấu trúc toàn bộ dự án, giao diện mới, bảo mật TLS và các tính năng còn thiếu.

**Cấu trúc**
- Thư mục chuẩn: `database/` (schema + migrations), `docs/`, `scripts/` (PowerShell), `config/` (cấu hình riêng từng máy), `data/` (dữ liệu khi chạy), Maven Wrapper (`mvnw`).
- Server tách tầng `net/` (MailServer, ClientHandler, CommandRouter), `security/`, `storage/`, `dao/jdbc/`; service dùng chung cho mọi kết nối (`ServerServices`); entry point `MailServerApplication`.
- Client tách `app/` (Session, Navigator), `api/` (MailApi có kiểu), `network/`, `service/`, `ui/` (component dùng lại).
- Migration chạy lại nhiều lần vẫn an toàn; đường dẫn tệp đính kèm lưu dạng tương đối.

**Bảo mật**
- TLS 1.3 bắt buộc; Server tự sinh chứng chỉ EC P-256; Client ghim chứng chỉ theo TOFU và cảnh báo khi chứng chỉ đổi.
- Khoá đăng nhập tạm sau 5 lần sai (theo email và IP), chính sách mật khẩu mạnh, giới hạn đăng ký theo IP, giới hạn tốc độ request mỗi kết nối.
- Đổi mật khẩu và "đăng xuất mọi thiết bị" thu hồi phiên tức thì (`SESSION_REVOKED`).
- Kiểm tra chữ ký đầu tệp (magic bytes), nhật ký bảo mật `data/logs/security.log`, mật khẩu MySQL ra khỏi file được commit.

**Tính năng**
- Thư gắn sao, đánh dấu chưa đọc, thư nháp (tự lưu), trả lời / trả lời tất cả / chuyển tiếp kèm tệp gốc.
- Đổi tên hiển thị, đổi mật khẩu, đăng xuất mọi thiết bị; thông báo desktop khi có thư mới; số chưa đọc trên tiêu đề cửa sổ.
- `MailSummary` có đoạn trích nội dung, cờ có tệp đính kèm, thư mục; nội dung thư tối đa 100 000 ký tự (MEDIUMTEXT).

**Giao diện**
- Thiết kế lại theo phong cách Gmail: bố cục 3 cột (thư mục | danh sách | khung đọc), icon Material Design (Ikonli), design tokens trong `app.css`, trạng thái đang tải / rỗng / lỗi, toast, phím tắt, cửa sổ soạn thư riêng, hộp thoại tài khoản và nhóm mới.

**Kiểm thử**
- 44 test tự động (TLS thật, bảo mật, định tuyến, TOFU) + `scripts/e2e.ps1` 51 kịch bản với MySQL thật.

---

## [1.0.4] - 2026-10-10

Xử lý phản hồi sau khi test thủ công (gửi file, trạng thái thư mới, real-time, thùng rác, tìm kiếm, soạn thư, nhóm: đều OK).

- Sửa giao diện đăng nhập/đăng ký: tiêu đề hiện nguyên văn `\n` và bị cắt chữ (thuộc tính FXML phải dùng `&#10;`); ô Máy chủ chuyển xuống cuối form với kiểu chữ nhỏ, con trỏ mặc định ở ô Email.
- Nhóm thư không gửi lại cho chính người gửi; nhóm chỉ có người gửi bị từ chối kèm thông báo rõ ràng.
- Sửa lỗi mở thư không bỏ được trạng thái chưa đọc khi một tài khoản có 2 bản của cùng thư (bản SENDER và bản nhận): `GET_MAIL_DETAIL` nhận `entryId` của bản được mở, thiếu `entryId` thì ưu tiên bản nhận.
- Chọn nhiều thư (Ctrl/Shift + click, Chọn tất cả) cho đánh dấu đã đọc, chuyển vào Thùng rác, khôi phục, xóa vĩnh viễn; Server nhận `{entryIds: [...]}` trong một request (tối đa 500) và chỉ đẩy một EVENT cho cả lô. Lựa chọn được giữ khi danh sách tự tải lại do EVENT.
- Màn hình chính: thẻ hồ sơ (ảnh đại diện chữ cái đầu, tên, email), số thư chưa đọc trên từng thư mục (`Folder.unreadCount`), phím tắt Enter/Delete, thanh công cụ không còn bị cắt chữ, thư đang chọn được tô nổi bật kể cả khi chưa đọc.
- Thêm `MailServiceTest` (5 test: loại người gửi khỏi nhóm, nhóm một mình, tự gửi cho mình, thao tác hàng loạt, payload lô không hợp lệ). Tổng 28 test.
- Tài liệu: PROTOCOL (entryIds, entryId khi mở thư, unreadCount, quy tắc nhóm), NETWORK_PROGRAMMING (gộp thao tác giảm số vòng gửi/nhận và EVENT), BUSINESS_RULES, FEATURES.

---

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
