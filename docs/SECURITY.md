# Bảo mật Mail System

Tài liệu này mô tả mô hình mối đe doạ, từng biện pháp bảo mật đã áp dụng, cách
cấu hình, và giới hạn còn lại. Phần giải thích khái niệm mạng (TLS, TOFU) nằm ở
[NETWORK_PROGRAMMING.md §12](NETWORK_PROGRAMMING.md#12-bảo-mật-tls-13-và-các-lớp-phòng-thủ).

## 1. Mô hình mối đe doạ

| Kẻ tấn công | Có thể làm gì | Hệ thống chặn bằng |
| --- | --- | --- |
| Người nghe lén trong cùng mạng LAN/Wi-Fi | Bắt gói (Wireshark), ARP spoofing | TLS 1.3 bắt buộc |
| Kẻ giả mạo máy chủ (man-in-the-middle) | Dựng Server giả để lấy mật khẩu | Ghim chứng chỉ TOFU; cảnh báo khi chứng chỉ đổi |
| Người dùng hợp lệ nhưng tò mò | Gửi request sửa `entryId`/`attachmentId` của người khác | Mọi truy vấn lọc theo `account_id` của phiên |
| Kẻ dò mật khẩu | Thử hàng nghìn mật khẩu | Khoá tạm theo email + IP, PBKDF2 chậm có chủ đích |
| Client độc hại / bị lỗi | Gửi frame khổng lồ, spam request, mở nghìn kết nối | Giới hạn frame, token bucket, giới hạn kết nối, idle timeout |
| Người gửi tệp độc hại | Đổi tên `virus.exe` thành `baocao.pdf` | Kiểm tra magic bytes, danh sách đuôi cho phép |
| Ai đó đọc được repo Git | Lấy mật khẩu MySQL | Bí mật không bao giờ nằm trong file được commit |

## 2. Bảo mật đường truyền

- **TLS 1.3 bắt buộc** cho mọi kết nối (`ProtocolConstants.TLS_PROTOCOLS`).
  Không có chế độ không mã hoá; client TCP thường bị Server ngắt ngay khi bắt tay.
- **Chứng chỉ Server** tự sinh ở lần chạy đầu: khoá EC P-256, ký SHA256withECDSA,
  hạn 10 năm, SAN gồm `localhost`, `127.0.0.1` và tên máy. Lưu tại
  `data/tls/server.p12`; mật khẩu keystore ngẫu nhiên lưu ở
  `data/tls/server.p12.pass` (hoặc đặt `tls.keystorePassword`). Thư mục `data/`
  bị `.gitignore`.
- **TOFU phía Client**: lần đầu kết nối tới `host:port` hiện dấu vân tay SHA-256
  để người dùng đối chiếu với dòng Server in ra:
  ```text
  [TLS] Dau van tay chung chi (SHA-256), dung de doi chieu khi client hoi:
        15:6B:61:2B:C1:0D:66:7B:...:A6:B4
  ```
  Dấu vân tay được ghi vào `~/.mailsystem/known_servers.properties`. Chứng chỉ
  khác lần trước → cảnh báo đỏ, mặc định huỷ kết nối trước khi gửi mật khẩu.
  Màn hình **Tài khoản & bảo mật → Kết nối** hiện giao thức, bộ mã hoá và dấu
  vân tay đang dùng.
- **Tạo lại chứng chỉ** (VD: lộ khoá riêng): dừng Server, xoá `data/tls/`, chạy
  lại. Mọi client sẽ thấy cảnh báo "chứng chỉ đã thay đổi" một lần; người dùng
  đối chiếu dấu vân tay mới rồi chấp nhận.

## 3. Xác thực và phiên

| Biện pháp | Chi tiết |
| --- | --- |
| Băm mật khẩu | PBKDF2-HMAC-SHA256, 210 000 vòng, salt ngẫu nhiên 16 byte, so sánh thời gian hằng |
| Chính sách mật khẩu | ≥ 8 ký tự, có chữ và số, ≤ 128 ký tự (chặn DoS băm), không thuộc danh sách phổ biến, không chứa phần tên của email |
| Không lộ email đã đăng ký | Sai email hay sai mật khẩu đều trả `"Email hoặc mật khẩu không đúng"`; email không tồn tại vẫn tốn thời gian băm như thật |
| Chống dò mật khẩu | 5 lần sai theo email (từ bất kỳ IP nào) hoặc 20 lần sai theo IP → khoá 15 phút. Khoá áp dụng như nhau với email có/không tồn tại. `CHANGE_PASSWORD` dùng chung bộ đếm |
| Giới hạn đăng ký | 10 tài khoản / IP / giờ |
| Phiên gắn với kết nối TLS | Không có token để đánh cắp; mất kết nối = phải đăng nhập lại |
| Thu hồi phiên | Đổi mật khẩu: mọi phiên KHÁC nhận `SESSION_REVOKED` và bị ngắt. "Đăng xuất khỏi mọi thiết bị": thu hồi tất cả |
| Không trả dữ liệu nhạy cảm | Không bao giờ gửi `password_hash`; lỗi nội bộ chỉ trả câu chung chung, chi tiết ghi log phía Server |

## 4. Phân quyền dữ liệu

- `CommandRouter` chặn mọi command trừ `PING`, `REGISTER`, `LOGIN` khi chưa đăng nhập.
- Mọi thao tác trên thư dùng `entryId` và lọc `WHERE account_id = <phiên>`: không
  thể đọc, đánh dấu, xoá thư của người khác dù đoán đúng id.
- Tệp đính kèm chỉ tải được nếu tài khoản có một bản thư chứa tệp đó; chuyển
  tiếp tệp cũng kiểm tra quyền xem tương tự.
- BCC chỉ hiện với người gửi và chính người nhận BCC đó.
- Chỉ chủ nhóm được thêm/xoá thành viên và xoá nhóm; chỉ thành viên được gửi tới nhóm.
- Thư nháp chỉ chủ sở hữu đọc/sửa/xoá được.

## 5. Chống lạm dụng tài nguyên (DoS)

| Giới hạn | Giá trị mặc định | Khoá cấu hình |
| --- | --- | --- |
| Kết nối đồng thời | 100 | `server.maxClients` |
| Kích thước một frame | 1 MiB ký tự | `ProtocolConstants.MAX_FRAME_CHARS` |
| Tốc độ request mỗi kết nối | 30/giây, dồn 120 | `security.requestsPerSecond`, `security.requestBurst` |
| Kết nối im lặng | 300 giây | `server.idleTimeoutSeconds` |
| Hàng đợi gửi (client đọc chậm) | 256 frame | `ClientHandler.OUTBOUND_QUEUE_CAPACITY` |
| Tệp dở dang mỗi kết nối | 5 | `ProtocolConstants.MAX_ATTACHMENTS_PER_MAIL` |
| Thư nháp mỗi tài khoản | 200 | `DraftService.MAX_DRAFTS` |
| Thao tác hàng loạt | 500 thư / request | `ProtocolConstants.MAX_ENTRIES_PER_REQUEST` |
| Nội dung thư | 100 000 ký tự | `ProtocolConstants.MAX_BODY_CHARS` |

## 6. Tệp đính kèm

- Chỉ nhận `pdf docx pptx txt jpg png zip`; tối đa 5 tệp, tổng 25 MiB.
- Server kiểm tra **chữ ký đầu tệp** khớp phần mở rộng (`FileSignatures`).
- Tên tệp lưu trên đĩa do Server sinh (UUID), DB chỉ lưu tên tương đối; đường
  dẫn được chuẩn hoá và phải nằm trong `data/attachments` (chống path traversal).
- Client lưu tệp tải về vào tệp tạm `.part`, kiểm tra đủ kích thước rồi mới đổi
  tên; tên tệp gợi ý được lọc bỏ ký tự đặc biệt.

## 7. Bí mật và cấu hình

- `mail-server/src/main/resources/server-defaults.properties` (được commit) **không chứa bí mật**.
- Mật khẩu MySQL đặt ở `config/server.properties` (bị `.gitignore`, mẫu:
  `config/server.properties.example`) hoặc biến môi trường `MAIL_DB_PASSWORD`.
- `scripts/setup-db.ps1` truyền mật khẩu MySQL qua biến môi trường `MYSQL_PWD`
  thay vì dòng lệnh (dòng lệnh có thể bị tiến trình khác đọc).

## 8. Nhật ký bảo mật

`data/logs/security.log` (đồng thời in ra console với tiền tố `[SECURITY]`) ghi:
`REGISTER`, `LOGIN`, `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOGIN_BLOCKED`,
`REGISTER_RATE_LIMITED`, `RATE_LIMITED`, `TLS_HANDSHAKE_FAILED`,
`CHANGE_PASSWORD`, `CHANGE_PASSWORD_FAILED`, `LOGOUT_ALL`, kèm thời điểm và IP.
Không bao giờ ghi mật khẩu hay nội dung thư; ký tự xuống dòng trong dữ liệu từ
client bị thay thế để chống giả mạo dòng log.

## 9. Kiểm chứng

- `SecurityComponentsTest`, `TofuTrustManagerTest`, `ServerNetworkTest`,
  `CommandRouterTest` (chạy bằng `scripts/test.ps1`, không cần MySQL).
- `scripts/e2e.ps1`: chạy với MySQL thật, gồm khoá đăng nhập, chặn tệp giả,
  client không TLS bị từ chối, đổi mật khẩu thu hồi phiên, không chuyển tiếp
  được tệp không có quyền xem.

## 10. Giới hạn còn lại

- TOFU phụ thuộc người dùng đối chiếu dấu vân tay ở lần kết nối đầu.
- Bộ đếm khoá đăng nhập nằm trong bộ nhớ: khởi động lại Server thì bộ đếm về 0.
- Kết nối MySQL mặc định không dùng TLS (`useSSL=false`) vì MySQL chạy cùng máy
  với Server. Nếu tách MySQL sang máy khác, bật `useSSL=true` trong `db.url`.
- Chưa có xác thực hai yếu tố và chưa mã hoá nội dung thư khi lưu trong DB.
