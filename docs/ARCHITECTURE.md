# Kiến trúc Mail System

## 1. Cây thư mục

```text
Mail-System/
├── pom.xml                      Maven parent: phiên bản thư viện dùng chung, JUnit, release 25
├── mvnw, mvnw.cmd, .mvn/        Maven Wrapper: build không cần cài Maven
├── config/
│   ├── server.properties.example   Mẫu cấu hình riêng từng máy (được commit)
│   └── server.properties           Mật khẩu MySQL của máy này (bị .gitignore)
├── database/
│   ├── schema.sql               Tạo CSDL mới
│   └── migrations/              Nâng cấp CSDL đã có (chạy lại nhiều lần vẫn an toàn)
├── scripts/                     setup-db, build, run-server, run-client, test, e2e, loadtest, package (PowerShell)
├── .github/workflows/ci.yml     CI: build + unit test (JDK 25) và e2e với MySQL 8 trên mỗi push
├── docs/                        Tài liệu (xem README)
├── data/                        Sinh ra khi chạy Server (bị .gitignore)
│   ├── attachments/             Nội dung tệp đính kèm (tên UUID)
│   ├── tls/server.p12           Khoá + chứng chỉ TLS tự sinh
│   └── logs/security.log        Nhật ký bảo mật
│
├── mail-common/                 HỢP ĐỒNG chung Client ↔ Server
│   └── com.mailsystem.common
│       ├── protocol/            RequestMessage, ResponseMessage, EventMessage, Command, EventName,
│       │                        FrameReader, ProtocolUtil, ProtocolConstants, ErrorCodes
│       ├── model/               Account, Folder, MailSummary, MailDetail, Attachment, Draft, MailGroupDTO,
│       │                        ReadReceipt, ScheduledMail, AccountSettings
│       └── security/            CertificateFingerprints
│
├── mail-server/
│   └── com.mailsystem.server
│       ├── MailServerApplication   Entry point: cấu hình → TLS → MySQL → service → tác vụ nền → giám sát → mở cổng
│       ├── config/              ServerConfig (đọc cấu hình nhiều lớp)
│       ├── net/                 MailServer (accept), ClientHandler (1 kết nối), CommandRouter, ConnectionContext,
│       │                        ServerThreads (virtual thread / thread pool)
│       ├── monitor/             DashboardServer (HTTP, 127.0.0.1), ServerMetrics; resources/monitor/dashboard.html
│       ├── service/             AuthService, AccountService, MailService, DraftService, FolderService,
│       │                        GroupService, ContactService, ServerServices
│       ├── dao/                 Interface truy cập dữ liệu
│       │   └── jdbc/            Cài đặt JDBC (PreparedStatement)
│       ├── db/                  DatabaseConnection (HikariCP, kiểm tra TLS tới MySQL)
│       ├── session/             SessionManager (accountId → các phiên), ClientSession, ResumeTokens
│       ├── security/            TlsContextFactory, PasswordHasher, PasswordPolicy, LoginThrottle,
│       │                        RateLimiter, SlidingWindowLimiter, FileSignatures, SecurityLog,
│       │                        Totp, ThrottleStore, DeviceRegistry
│       ├── storage/             AttachmentStorage
│       ├── transfer/            UploadManager (upload theo khối), UploadRegistry (giữ qua các kết nối)
│       └── tools/               ManualTestClient, EndToEndCheck, LoadTest
│
└── mail-client/
    └── com.mailsystem.client
        ├── MailClientApp        Entry point JavaFX; Launcher (entry point cho bản đóng gói)
        ├── app/                 Session (trạng thái đăng nhập), Navigator (màn hình, cửa sổ, theme),
        │                        Reconnector (tự kết nối lại)
        ├── network/             ServerConnection, Connector, TlsConnector, TofuTrustManager, KnownServers,
        │                        DeviceIdentity (chứng chỉ thiết bị)
        ├── api/                 MailApi (lớp bọc có kiểu quanh giao thức), ApiException
        ├── service/             AttachmentTransfer (upload/download theo khối), DesktopNotifier
        ├── controller/          Login, Register, Main, Compose, Groups, Account
        └── ui/                  Component dùng lại: Avatar, Toast, EmptyState, MailCell, DraftCell,
                                 MailReader, NavButton, PasswordField2, PasswordStrength, AutoComplete,
                                 ContactSuggestions, Dialogs, Icons, Formats, Fx, ScheduledCell,
                                 AttachmentPreview
        resources/
        ├── fxml/                login, register, main, compose, groups, account
        └── css/app.css          Design tokens + style mọi component; dark.css chỉ đổi giá trị token
```

## 2. Các tầng phía Server

```text
TCP/TLS ─▶ net (MailServer, ClientHandler) ─▶ CommandRouter ─▶ service ─▶ dao ─▶ MySQL
                         │                                       │
                         └── session (SessionManager) ◀── đẩy EVENT ┘
```

| Tầng | Được phép | Không được phép |
| --- | --- | --- |
| `net` | Đọc/ghi frame, giới hạn tốc độ, trạng thái kết nối | Biết SQL hay quy tắc nghiệp vụ |
| `service` | Kiểm tra dữ liệu vào, quyền, quy tắc nghiệp vụ, transaction, đẩy EVENT | Đụng tới socket |
| `dao` | Câu lệnh SQL | Quy tắc nghiệp vụ |
| `security` | Thuật toán bảo mật dùng lại được | Phụ thuộc service/dao |

Service **dùng chung cho mọi kết nối** (không giữ trạng thái theo kết nối);
trạng thái theo kết nối (tài khoản, tệp đang tải lên, IP) nằm ở `ConnectionContext`.

## 3. Các tầng phía Client

```text
FXML + Controller ─▶ MailApi ─▶ ServerConnection ─▶ TlsConnector ─▶ TLS/TCP
      ▲                                │
      └──── Platform.runLater ◀── listener thread (RESPONSE theo requestId, EVENT)
```

- **Controller** chỉ lo giao diện, gọi `MailApi` và nhận kết quả qua `Fx.then(...)`
  (tự chuyển về UI thread, đổi lỗi thành câu tiếng Việt).
- **MailApi** là nơi DUY NHẤT biết hình dạng JSON của từng command.
- **ServerConnection** lo một kết nối: ghép response theo `requestId`, timeout,
  heartbeat, báo mất kết nối.
- **ui/** chứa component dùng lại; giao diện thống nhất nhờ token màu/khoảng cách
  ở đầu `app.css`.

## 4. Đường đi của một thao tác: gắn sao một thư

1. Người dùng bấm ngôi sao trong `MailCell` → `MainController.toggleStar(mail)`.
2. `MailApi.setStarred([entryId], true)` dựng payload `{entryIds, starred}`.
3. `ServerConnection.sendRequest` gắn `requestId`, ghi frame qua TLS.
4. `ClientHandler` đọc frame → kiểm tra tốc độ → `CommandRouter.dispatch(SET_STARRED)`.
5. Route `secured(...)` kiểm tra đã đăng nhập → `MailService.setStarred`.
6. `applyToEntries` gọi `MailDAO.setStarred` cho từng `entryId` (lọc theo `account_id`).
7. `SessionManager.pushEvent(accountId, MAIL_STARRED)` tới mọi phiên của tài khoản.
8. RESPONSE `{processed, requested}` về Client → `Fx.then` cập nhật ô thư trên UI thread.
9. Các thiết bị khác nhận EVENT → tải lại danh sách (gộp nhiều EVENT bằng debounce).

## 5. Thêm một tính năng mới (ví dụ: "Báo cáo spam")

1. **Giao thức** (`mail-common`): thêm `REPORT_SPAM` vào `Command`; nếu cần,
   thêm `EventName`. Ghi payload/response vào `docs/PROTOCOL.md`.
2. **CSDL**: thêm cột/bảng vào `database/schema.sql` và tạo
   `database/migrations/<yyyyMMdd>_<mo_ta>.sql` (dùng mẫu kiểm tra
   `information_schema` để chạy lại an toàn).
3. **DAO**: thêm method vào interface trong `dao/`, cài đặt trong `dao/jdbc/`.
4. **Service**: thêm method vào service phù hợp (kiểm tra dữ liệu vào, quyền, đẩy EVENT).
5. **Route**: thêm một dòng `secured(Command.REPORT_SPAM, ...)` trong `CommandRouter`
   (`CommandRouterTest` sẽ báo lỗi nếu quên).
6. **Client**: thêm method trong `MailApi`, rồi gọi từ controller qua `Fx.then`.
7. **Test**: unit test cho service (DAO giả bằng `Proxy`, xem `MailServiceTest`),
   và một kịch bản trong `EndToEndCheck` nếu cần MySQL thật.
8. Cập nhật `docs/FEATURES.md` và `docs/CHANGELOG.md`.

## 6. Quy ước

- Thông báo cho người dùng: tiếng Việt có dấu. Log console: tiếng Việt không dấu
  (console Windows không hiển thị đúng UTF-8).
- Mọi giới hạn dùng chung hai phía để trong `ProtocolConstants`.
- Không chặn UI thread: mọi I/O (mạng, đĩa) chạy nền, kết quả về qua `Platform.runLater`.
- Không commit bí mật; cấu hình riêng để trong `config/server.properties` hoặc biến môi trường.
- Trước khi push: `scripts/test.ps1` (bắt buộc), `scripts/e2e.ps1` (khi đổi Server/DB).
