# Mail System — Client/Server (TCP Socket + MySQL)

Hệ thống mail nội bộ theo mô hình Client–Server, đồ án môn **Lập trình mạng**:

- Nhiều Client JavaFX kết nối **đồng thời** tới một Server qua **TCP Socket**.
- Giao tiếp bằng giao thức **JSON theo dòng** tự thiết kế, không dùng SMTP/IMAP hay REST.
- Server **chủ động đẩy** thông báo (thư mới, đã đọc, xoá, khôi phục) xuống mọi
  thiết bị đang đăng nhập, không cần polling.
- Tệp đính kèm truyền **theo từng khối 512 KB** ở cả hai chiều.

| Tài liệu | Nội dung |
| --- | --- |
| [docs/NETWORK_PROGRAMMING.md](docs/NETWORK_PROGRAMMING.md) | **Kiến thức lập trình mạng** áp dụng trong đồ án: socket, framing, đồng thời, server push, timeout/heartbeat, truyền tệp theo khối, bảo mật, câu hỏi ôn tập |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Đặc tả giao thức: định dạng gói tin, mọi command/event, ví dụ phiên làm việc |
| [BUSINESS_RULES.md](BUSINESS_RULES.md) | Quy tắc nghiệp vụ |
| [FEATURES.md](FEATURES.md) | Chức năng người dùng và ràng buộc |
| [DAILY_LOG.md](DAILY_LOG.md) | Nhật ký thay đổi |

## 1. Kiến trúc

```text
mail-system/                (Maven parent - pom.xml)
├── mail-common/            Hợp đồng giao thức dùng chung: RequestMessage/ResponseMessage/EventMessage,
│                           Command, EventName, FrameReader, ProtocolConstants, Model
├── mail-server/            MailServer (accept + thread pool) → ClientHandler (1 kết nối)
│                           → Service → DAO (JDBC) → MySQL; SessionManager (server push)
├── mail-client/            JavaFX: Controller → ServerConnection (1 socket, listener thread,
│                           CompletableFuture theo requestId, heartbeat)
├── docs/                   Tài liệu kiến thức mạng + đặc tả giao thức
├── migrations/             Script nâng cấp CSDL đã tồn tại
└── schema.sql              Tạo CSDL mới
```

```text
Client A ──┐                       ┌─ ClientHandler A ─┐
Client A'──┼── TCP :5000 ── MailServer ─ ClientHandler A'─┼─ Service ─ DAO ─ MySQL
Client B ──┘   (JSON/dòng)         └─ ClientHandler B ─┘
                                          ▲
                     SessionManager: accountId → {các phiên} ─ đẩy EVENT
```

**Quy tắc quan trọng**: `mail-common` là "hợp đồng" chung. Mọi thay đổi ở đây
ảnh hưởng **cả** Server lẫn Client, nên phải báo cả nhóm trước và chạy lại
`mvn clean install` ở thư mục gốc.

## 2. Chuẩn bị môi trường

- **JDK 17** trở lên (`java -version`)
- **Maven 3.8+** (`mvn -version`). Không cần cài JavaFX SDK riêng, Maven tự tải.
- **MySQL Server 8.x**
- IDE khuyến nghị: IntelliJ IDEA (tự nhận Maven multi-module)

Chưa có Maven trên Windows:

1. Tải bản zip từ maven.apache.org rồi giải nén.
2. Thêm thư mục `bin` của Maven vào biến môi trường `PATH` (Start → *Edit the
   system environment variables* → Environment Variables → System variables →
   `Path` → Edit).
3. Mở terminal mới, chạy `mvn -version` để kiểm tra.

## 3. Cài đặt và chạy

### 3.1 Cơ sở dữ liệu

- **CSDL mới**: chạy toàn bộ [schema.sql](schema.sql).
- **CSDL đã có từ trước**: chạy lần lượt các file trong [migrations/](migrations)
  theo thứ tự tên (ngày), mỗi file một lần:
  1. `20261006_restore_original_mail_folder.sql`: lưu thư mục gốc để khôi phục thư.
  2. `20261008_unique_group_name.sql`: tên nhóm là duy nhất.

### 3.2 Cấu hình Server

Sửa [mail-server/src/main/resources/db.properties](mail-server/src/main/resources/db.properties),
**hoặc** ghi đè bằng biến môi trường (không phải sửa file đã commit):

| Khoá trong `db.properties` | Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- | --- |
| `db.url` | `MAIL_DB_URL` | `jdbc:mysql://localhost:3306/mail_system?…` | Chuỗi kết nối MySQL |
| `db.username` | `MAIL_DB_USERNAME` | `root` | |
| `db.password` | `MAIL_DB_PASSWORD` | | |
| `server.port` | `MAIL_SERVER_PORT` | `5000` | Cổng TCP lắng nghe |
| `server.maxClients` | `MAIL_SERVER_MAX_CLIENTS` | `100` | Số kết nối đồng thời tối đa |
| `server.idleTimeoutSeconds` | `MAIL_SERVER_IDLE_TIMEOUT` | `300` | Đóng kết nối im lặng quá lâu |
| `attachments.dir` | `MAIL_ATTACHMENTS_DIR` | `attachments` | Thư mục lưu tệp đính kèm |

Thứ tự ưu tiên: `-Dkey=value` > biến môi trường > `db.properties` > mặc định.

### 3.3 Build và chạy

```bash
# 1. Build toàn bộ (bắt buộc sau mỗi lần sửa mail-common)
mvn clean install

# 2. Chạy Server
mvn -f mail-server/pom.xml exec:java -Dexec.mainClass="com.mailsystem.server.MailServer"
#    (hoặc Run MailServer.java trong IDE)

# 3. Chạy Client (mở nhiều cửa sổ để thử đa client, đa thiết bị)
mvn -f mail-client/pom.xml javafx:run
```

Các lệnh chạy từng module dùng artifact `mail-common` đã cài trong Maven local
repository. Nếu chỉ chạy `mvn test`, artifact đó không được cập nhật và hai phía
có thể dùng giao thức cũ.

### 3.4 Thử qua mạng LAN

1. Trên máy chạy Server: cho phép **Inbound TCP 5000** trong Windows Defender
   Firewall, rồi xem IP bằng `ipconfig` (VD `192.168.1.10`).
2. Trên máy khác: mở Client, nhập `192.168.1.10:5000` vào ô **Máy chủ** ở màn
   hình đăng nhập. Có thể đặt sẵn qua biến môi trường `MAIL_SERVER_HOST` và
   `MAIL_SERVER_PORT`.

Client chỉ kết nối khi bấm Đăng nhập/Đăng ký, nên app vẫn mở được khi Server
chưa chạy. Nếu mất kết nối, Client cảnh báo và quay về màn hình đăng nhập.

### 3.5 Kiểm thử

```bash
mvn test        # 28 test, không cần MySQL: framing, TCP thật, phiên, upload, nghiệp vụ thư, mất kết nối
```

Test thủ công Server không cần giao diện:

```bash
mvn -f mail-server/pom.xml exec:java -Dexec.mainClass="com.mailsystem.server.tools.ManualTestClient"
```

Gõ `PING`, `REGISTER`, `LOGIN`… cùng payload JSON. Công cụ in mọi frame Server
gửi về, **kể cả EVENT**. Mở hai cửa sổ, đăng nhập hai tài khoản và gửi thư cho
nhau để thấy server push. Có thể gõ tay bằng `ncat localhost 5000`. Cách quan
sát bằng Wireshark xem trong
[NETWORK_PROGRAMMING.md §13](docs/NETWORK_PROGRAMMING.md#13-quan-sát-và-kiểm-thử).

## 4. Chức năng chính

- Đăng ký, đăng nhập (mật khẩu PBKDF2), đăng xuất; một tài khoản đăng nhập
  nhiều thiết bị cùng lúc.
- Hộp thư đến / Đã gửi / Thùng rác; thư chưa đọc in đậm; mở thư tự đánh dấu đã đọc.
- Gửi thư tới nhiều người ở To/CC/BCC, hoặc tới **nhóm thư**. Một người nhận
  sai thì toàn bộ thư bị từ chối.
- Tệp đính kèm: tối đa 5 tệp / 25 MB, upload và download theo khối có tiến độ.
- Chuyển vào thùng rác, khôi phục về đúng thư mục gốc, xoá vĩnh viễn, dọn thùng rác.
- Tìm kiếm theo từ khoá và lọc theo người gửi.
- Đồng bộ thời gian thực mọi thay đổi tới các phiên đang mở.

Chi tiết xem [FEATURES.md](FEATURES.md).

## 5. Quy trình làm việc nhóm

### 5.1 Phân công

| Người | Module | Việc |
| --- | --- | --- |
| **TV2-Đạt** | `mail-server` | DAO (JDBC theo `schema.sql`) + Service (Auth, Mail, Folder, Group) |
| **TV3-Huy** | `mail-client` (giao diện) | Các file `.fxml`, Register/MailDetail/GroupManagement Controller |
| **TV4-Quang** | `mail-client` (network) + ghép nối | `ServerConnection`, nối Controller với Command, EVENT real-time, test luồng Client ↔ Server |

### 5.2 Git

- Nhánh `main` chỉ chứa code đã được leader review và merge. **Không code trực tiếp trên `main`.**
- Mỗi người làm trên nhánh riêng (`feature/server`, `feature/client-ui`,
  `feature/integration`, …). Commit thường xuyên với tiền tố module, VD:
  `[server] them AuthService.login`. Push rồi tạo Pull Request để leader review.
- **Luôn `git pull origin main` trước khi bắt đầu code mỗi buổi.**

### 5.3 Definition of Done

- Một command chỉ được coi là xong khi đã thử bằng `ManualTestClient` (hoặc UI)
  cho **cả trường hợp thành công lẫn ít nhất một trường hợp lỗi**. VD: LOGIN sai
  mật khẩu phải trả `ERROR`, không được crash hay trả `OK` nhầm.
- `mvn clean install` (gồm `mvn test`) phải chạy sạch trước khi push.

### 5.4 Dùng AI hỗ trợ viết code

Khi nhờ AI viết code cho một phần cụ thể, luôn đính kèm:

- File interface/class tương ứng.
- [BUSINESS_RULES.md](BUSINESS_RULES.md), để AI hiểu đúng quy tắc nghiệp vụ.
- [schema.sql](schema.sql) nếu viết DAO.
- [docs/PROTOCOL.md](docs/PROTOCOL.md) nếu viết Service, Controller hoặc phần mạng.

Mục đích là để AI sinh code đúng kiến trúc và nghiệp vụ đã thống nhất, không tự
bịa cấu trúc hay quy tắc khác.
