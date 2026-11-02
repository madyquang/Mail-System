# Mail System — Client/Server qua TCP + TLS 1.3

Hệ thống mail nội bộ theo mô hình Client–Server, đồ án môn **Lập trình mạng**:

- Nhiều Client JavaFX kết nối **đồng thời** tới một Server qua **TCP socket được mã hoá TLS 1.3**.
- Giao thức **JSON theo dòng** tự thiết kế (REQUEST / RESPONSE / EVENT), không dùng SMTP/IMAP hay REST.
- Server **chủ động đẩy** thay đổi tới mọi thiết bị đang đăng nhập (thư mới, đã đọc, gắn sao, xoá...).
- Tệp đính kèm truyền **theo khối 512 KB** hai chiều, có tiến độ, **tải tiếp** sau khi rớt mạng.
- Giao diện desktop 3 cột kiểu Gmail (sáng / tối): thư mục | danh sách thư | khung đọc thư.
- v3: tự kết nối lại, hội thoại, thư mục tự tạo + kéo thả, hẹn giờ gửi, thông báo khi được thêm vào nhóm, báo đã đọc,
  xem trước tệp, xác minh 2 bước (TOTP), chứng chỉ thiết bị (mutual TLS), bảng giám sát HTTP,
  virtual thread + HikariCP, MySQL qua TLS, CI, kiểm thử tải, đóng gói jpackage.

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

- **JDK 25+ (LTS)** (`java -version`). Không cần cài Maven: dự án có Maven Wrapper (`mvnw.cmd`).
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
[MailServer] Ket noi MySQL ma hoa TLS: TLS_AES_256_GCM_SHA384
[MailServer] Dang lang nghe tai cong 5000 (TLS 1.3), toi da 100 ket noi, virtual thread, chung chi thiet bi: want
[Monitor] Bang giam sat (chi mo duoc tren may nay): http://127.0.0.1:8090/?token=...
```

Mở đường dẫn `[Monitor]` trên chính máy Server để xem bảng giám sát: kết nối
đang mở (có nút ngắt), request/giây, độ trễ, pool MySQL, thiết bị chờ duyệt,
nhật ký bảo mật.

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
| `db.url` | `MAIL_DB_URL` | `jdbc:mysql://localhost:3306/mail_system?sslMode=PREFERRED...` | Chuỗi kết nối MySQL (`sslMode=REQUIRED` khi MySQL ở máy khác) |
| `db.poolSize` | `MAIL_DB_POOL_SIZE` | `10` | Số kết nối MySQL tối đa (HikariCP) |
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
| `security.resumeTokenMinutes` | `MAIL_RESUME_TOKEN_MINUTES` | `720` | Hạn token tự kết nối lại |
| `tls.clientAuth` | `MAIL_TLS_CLIENT_AUTH` | `want` | Chứng chỉ thiết bị: `off` / `want` / `need` |
| `server.threads` | `MAIL_SERVER_THREADS` | `auto` | `virtual` (mặc định) / `platform` |
| `monitor.enabled` / `monitor.port` / `monitor.token` | `MAIL_MONITOR_*` | `true` / `8090` / ngẫu nhiên | Bảng giám sát HTTP (chỉ 127.0.0.1) |
| `upload.expiryMinutes` | `MAIL_UPLOAD_EXPIRY_MINUTES` | `30` | Giữ tệp tải lên dở để tải tiếp |

## 4. Kết nối Client từ thiết bị khác

Server nghe trên **mọi địa chỉ mạng** của máy (không chỉ `localhost`), nên máy khác
trong cùng mạng kết nối được, chỉ cần biết IP của máy Server và mở tường lửa.
Máy Client **không cần** MySQL và không cần `config/server.properties`.

```text
Máy A (Server + MySQL)  192.168.1.10:5000  ◀── TLS 1.3 ──  Máy B (Client)
                                           ◀── TLS 1.3 ──  Máy C (Client)
```

### 4.1 Trên máy Server

1. **Chạy Server** như bình thường: `.\scripts\run-server.ps1`. Giữ cửa sổ này mở; dòng
   `[TLS] Dau van tay ...` in lúc khởi động sẽ dùng ở bước 4.3.
2. **Xem địa chỉ IP** của máy:
   ```powershell
   ipconfig        # tìm "IPv4 Address" của Wi-Fi / Ethernet đang dùng, VD 192.168.1.10
   ```
   Bỏ qua các địa chỉ của card ảo (VirtualBox, WSL, Hyper-V, VPN).
3. **Mở cổng 5000 trên tường lửa** (PowerShell chạy bằng quyền Administrator, làm một lần):
   ```powershell
   New-NetFirewallRule -DisplayName "Mail System 5000" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow -Profile Private
   ```
   Mạng Wi-Fi của máy phải là **Private** (Settings → Network & internet → Wi-Fi → Network
   profile type). Nếu mạng đang là Public thì đổi sang Private, hoặc thêm `,Public` vào
   `-Profile` (không khuyến khích ở Wi-Fi công cộng).

### 4.2 Trên máy Client

Cài Client theo **một** trong hai cách:

| Cách | Cần gì trên máy Client | Chạy |
| --- | --- | --- |
| Từ mã nguồn | JDK 25+, bản sao repo này | `.\scripts\run-client.ps1 -Server 192.168.1.10:5000` |
| Bản đóng gói | Không cần cài Java | Trên máy có JDK chạy `.\scripts\package.ps1`, chép cả thư mục `dist\MailSystem` sang máy Client, mở `MailSystem.exe` |

Với bản đóng gói (hoặc nếu chạy Client không kèm `-Server`): ở màn hình đăng nhập bấm
**Máy chủ: localhost:5000** ở cuối thẻ, nhập `192.168.1.10:5000` rồi đăng nhập. Địa chỉ được
nhớ cho các lần sau.

Kiểm tra trước nếu chưa chắc mạng thông:

```powershell
Test-NetConnection 192.168.1.10 -Port 5000    # TcpTestSucceeded : True là được
```

### 4.3 Lần kết nối đầu tiên: xác nhận chứng chỉ

Client hiện hộp thoại **"Kết nối lần đầu tới 192.168.1.10:5000"** kèm dấu vân tay SHA-256.
So với dòng `[TLS] Dau van tay` trên console của máy Server (hoặc nhờ người chạy Server
gửi dòng đó qua tin nhắn). Khớp thì bấm **Tin và kết nối**. Client ghi nhớ và các lần sau
không hỏi lại. Dấu vân tay được ghi theo từng địa chỉ, nên đổi từ `localhost:5000` sang
`192.168.1.10:5000` sẽ được hỏi một lần nữa.

Chưa có tài khoản thì bấm **Tạo tài khoản** ngay trên máy Client.

### 4.4 Lỗi thường gặp

| Client báo | Nguyên nhân thường gặp | Cách xử lý |
| --- | --- | --- |
| `Không kết nối được tới ... (Connection timed out)` | Sai IP, tường lửa chặn, hai máy khác mạng, Wi-Fi bật "cách ly thiết bị" (Wi-Fi khách / quán cà phê) | Kiểm tra lại IP và luật tường lửa ở 4.1; chạy `Test-NetConnection`; dùng chung một Wi-Fi nhà hoặc hotspot điện thoại |
| `Không kết nối được tới ... (Connection refused)` | Server chưa chạy hoặc sai cổng | Chạy Server, kiểm tra cổng trong dòng `Dang lang nghe tai cong ...` |
| Cảnh báo đỏ **chứng chỉ đã thay đổi** | Server tạo chứng chỉ mới (xoá `data/tls/`, chạy Server ở thư mục khác), hoặc có máy giả mạo | Chỉ chấp nhận khi dấu vân tay mới khớp với console của Server |
| `Máy chủ đang quá tải, vui lòng thử lại sau.` | Đã đủ `server.maxClients` kết nối (mặc định 100) | Đợi, hoặc tăng `server.maxClients` |
| `Thiết bị này chưa được quản trị viên phê duyệt...` | Server đặt `tls.clientAuth=need` | Trên máy Server mở bảng giám sát (dòng `[Monitor]`), phê duyệt dấu vân tay thiết bị mà Client hiện ở **Tài khoản → Kết nối** |

> Bảng giám sát (cổng 8090) chỉ mở được trên chính máy Server, máy khác **không** truy cập
> được; chỉ cổng 5000 cần mở ra mạng. Để Client kết nối qua Internet (không cùng mạng
> LAN) thì cần mở cổng trên router (port forwarding) hoặc dùng VPN như Tailscale; khi đó
> càng phải đối chiếu dấu vân tay cẩn thận.

## 5. Kiểm thử

```powershell
.\scripts\test.ps1       # 54 unit/integration test (TLS thật, không cần MySQL)
.\scripts\e2e.ps1        # 109 kịch bản end-to-end với MySQL thật (Server riêng ở cổng 5098, tự dọn dữ liệu)
.\scripts\loadtest.ps1 -Clients 100 -Seconds 20   # kiểm thử tải: request/giây, độ trễ p50/p95/p99
```

GitHub Actions ([.github/workflows/ci.yml](.github/workflows/ci.yml)) chạy build + test trên JDK 25,
rồi chạy e2e với MySQL 8 trong container, ở mỗi lần push / pull request.

## 5b. Đóng gói

```powershell
.\scripts\package.ps1              # dist\MailSystem\MailSystem.exe và dist\MailServer\MailServer.exe
.\scripts\package.ps1 -Installer   # thêm bộ cài .msi (cần WiX Toolset)
```

Bản đóng gói kèm sẵn Java runtime thu gọn: máy người dùng **không cần cài Java**.

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
scripts/       setup-db, build, run-server, run-client, test, e2e, loadtest, package
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
