# Mail System — Client/Server qua TCP + TLS 1.3

Hệ thống mail nội bộ theo mô hình Client–Server, đồ án môn **Lập trình mạng**:

- Nhiều Client JavaFX kết nối **đồng thời** tới một Server qua **TCP socket được mã hoá TLS 1.3**.
- Giao thức **JSON theo dòng** tự thiết kế (REQUEST / RESPONSE / EVENT), không dùng SMTP/IMAP hay REST.
- Server **chủ động đẩy** thay đổi tới mọi thiết bị đang đăng nhập (thư mới, đã đọc, gắn sao, xoá...).
- Tệp đính kèm truyền **theo khối 512 KB** hai chiều, có tiến độ.
- Giao diện desktop 3 cột kiểu Gmail: thư mục | danh sách thư | khung đọc thư.

| Tài liệu | Nội dung |
| --- | --- |
| [docs/NETWORK_PROGRAMMING.md](docs/NETWORK_PROGRAMMING.md) | **Kiến thức lập trình mạng** trong đồ án: socket, framing, đồng thời, server push, timeout/heartbeat, truyền theo khối, TLS, câu hỏi ôn tập |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Đặc tả giao thức: gói tin, mọi command/event, ví dụ phiên làm việc |
| [docs/SECURITY.md](docs/SECURITY.md) | Mô hình mối đe doạ và mọi biện pháp bảo mật |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Cây thư mục, các tầng, cách thêm tính năng mới |
| [docs/FEATURES.md](docs/FEATURES.md) | Chức năng người dùng và ràng buộc |
| [docs/BUSINESS_RULES.md](docs/BUSINESS_RULES.md) | Quy tắc nghiệp vụ |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Nhật ký thay đổi |

## 1. Yêu cầu

- **JDK 17+** (`java -version`). Không cần cài Maven: dự án có Maven Wrapper (`mvnw.cmd`).
- **MySQL Server 8.x** đang chạy.
- Windows PowerShell để chạy các script trong `scripts/` (hoặc gõ lệnh Maven tương ứng trên hệ điều hành khác).

> Lần đầu chạy script PowerShell có thể bị chặn bởi Execution Policy. Chạy một lần:
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`

## 2. Bắt đầu nhanh

```powershell
.\scripts\setup-db.ps1      # 1. Tạo/nâng cấp database (tự sao lưu trước), tạo config/server.properties
.\scripts\build.ps1         # 2. Build + chạy test (không cần MySQL)
.\scripts\run-server.ps1    # 3. Chạy Server (terminal 1)
.\scripts\run-client.ps1    # 4. Mở Client (terminal 2, 3... để thử nhiều client)
```

Lần đầu Server chạy sẽ tự tạo chứng chỉ TLS và in dấu vân tay:

```text
[TLS] Dau van tay chung chi (SHA-256), dung de doi chieu khi client hoi:
      15:6B:61:2B:C1:0D:66:7B:...
[MailServer] Ket noi MySQL thanh cong: jdbc:mysql://localhost:3306/mail_system
[MailServer] Dang lang nghe tai cong 5000 (TLS 1.3), toi da 100 ket noi
```

Lần đầu Client kết nối sẽ hỏi có tin chứng chỉ này không: đối chiếu dấu vân tay
rồi bấm **Tin và kết nối**. Sau đó bấm **Tạo tài khoản** để đăng ký.

<details><summary>Không dùng script (lệnh Maven trực tiếp)</summary>

```powershell
# Database: chạy database/schema.sql (DB mới) hoặc database/migrations/*.sql (DB cũ) trong MySQL Workbench
Copy-Item config\server.properties.example config\server.properties   # rồi sửa db.password
.\mvnw.cmd clean install
.\mvnw.cmd -f mail-server/pom.xml exec:java
.\mvnw.cmd -f mail-client/pom.xml javafx:run
```
Trong IntelliJ: chạy `MailServerApplication` và `MailClientApp`.
</details>

## 3. Cấu hình Server

Thứ tự ưu tiên: `-Dkey=value` > biến môi trường > `config/server.properties` (riêng từng
máy, bị `.gitignore`) > `server-defaults.properties` (mặc định, được commit).

| Khoá | Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- | --- |
| `db.url` | `MAIL_DB_URL` | `jdbc:mysql://localhost:3306/mail_system?...` | Chuỗi kết nối MySQL |
| `db.username` / `db.password` | `MAIL_DB_USERNAME` / `MAIL_DB_PASSWORD` | `root` / trống | Tài khoản MySQL |
| `server.port` | `MAIL_SERVER_PORT` | `5000` | Cổng TLS |
| `server.maxClients` | `MAIL_SERVER_MAX_CLIENTS` | `100` | Kết nối đồng thời tối đa |
| `server.idleTimeoutSeconds` | `MAIL_SERVER_IDLE_TIMEOUT` | `300` | Đóng kết nối im lặng |
| `data.dir` | `MAIL_DATA_DIR` | `data` | Tệp đính kèm, keystore TLS, log |
| `tls.keystorePassword` | `MAIL_TLS_KEYSTORE_PASSWORD` | tự sinh | Mật khẩu keystore |
| `security.loginMaxFailures` | `MAIL_LOGIN_MAX_FAILURES` | `5` | Số lần sai trước khi khoá |
| `security.loginLockMinutes` | `MAIL_LOGIN_LOCK_MINUTES` | `15` | Thời gian khoá |
| `security.requestsPerSecond` / `security.requestBurst` | `MAIL_REQUESTS_PER_SECOND` / `MAIL_REQUEST_BURST` | `30` / `120` | Giới hạn tốc độ mỗi kết nối |
| `security.registrationsPerHourPerIp` | `MAIL_REGISTRATIONS_PER_HOUR` | `10` | Giới hạn đăng ký |

## 4. Chạy qua mạng LAN

1. Máy Server: cho phép **Inbound TCP 5000** trong Windows Defender Firewall; xem IP bằng `ipconfig` (VD `192.168.1.10`).
2. Máy khác: `.\scripts\run-client.ps1 -Server 192.168.1.10:5000`, hoặc mở mục **Máy chủ** ở cuối màn hình đăng nhập.
3. Đối chiếu dấu vân tay chứng chỉ với console của Server ở lần kết nối đầu.

## 5. Kiểm thử

```powershell
.\scripts\test.ps1   # 44 unit/integration test qua TLS thật, không cần MySQL
.\scripts\e2e.ps1    # ~50 kịch bản end-to-end với MySQL thật (Server riêng ở cổng 5098, tự dọn dữ liệu)
```

Thử giao thức bằng tay (kết nối TLS, hỏi xác nhận dấu vân tay, in cả EVENT):

```powershell
.\mvnw.cmd -q -f mail-server/pom.xml exec:java "-Dexec.mainClass=com.mailsystem.server.tools.ManualTestClient"
```

> PowerShell: đặt **cả cụm** `-D...=...` trong ngoặc kép như trên; nếu không, PowerShell tách tham số tại
> dấu chấm và Maven báo `Unknown lifecycle phase ".mainClass=..."`.

## 6. Cấu trúc dự án

```text
mail-common/   Hợp đồng giao thức + model dùng chung (đổi ở đây phải build lại cả hai phía)
mail-server/   net/ (socket, TLS, định tuyến) · service/ · dao/ · security/ · session/ · storage/ · transfer/
mail-client/   network/ (TLS, TOFU) · api/ · controller/ · ui/ (component dùng lại) · service/
database/      schema.sql + migrations/
scripts/       setup-db, build, run-server, run-client, test, e2e
config/        server.properties.example (mẫu), server.properties (bí mật, không commit)
docs/          tài liệu
```

Chi tiết: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## 7. Làm việc nhóm

- Nhánh `main` chỉ chứa code đã review. Làm trên nhánh riêng, mở Pull Request.
- Trước khi push: `.\scripts\test.ps1` phải xanh; đổi Server/DB thì chạy thêm `.\scripts\e2e.ps1`.
- Đổi `mail-common` → báo cả nhóm và `.\scripts\build.ps1` lại (hai phía phải cùng phiên bản giao thức).
- Đổi CSDL → thêm file migration mới trong `database/migrations/`, không sửa migration cũ.
- Không commit bí mật: mật khẩu chỉ nằm ở `config/server.properties` hoặc biến môi trường.
- Khi nhờ AI viết code: đính kèm [BUSINESS_RULES.md](docs/BUSINESS_RULES.md), [PROTOCOL.md](docs/PROTOCOL.md),
  [ARCHITECTURE.md](docs/ARCHITECTURE.md) và file liên quan để AI theo đúng kiến trúc, nghiệp vụ.
