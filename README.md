# Mail System — Client/Server (TCP Socket + MySQL)

## 0. Chuẩn bị môi trường (làm trước khi động vào code)

Mỗi thành viên cần cài trên máy mình:
- **JDK 17** trở lên (kiểm tra: `java -version`)
- **Maven** (kiểm tra: `mvn -version`) — không cần cài JavaFX SDK riêng, project đã khai
  báo JavaFX qua Maven dependency, Maven sẽ tự tải.
- **MySQL Server 8.x** đã chạy sẵn trên máy (hoặc dùng chung 1 server MySQL trên máy
  của 1 bạn trong nhóm, các máy khác trỏ `db.url` vào IP máy đó — chỉ dùng khi mọi
  người trong nhóm cùng mạng LAN).
- **IDE khuyến nghị**: IntelliJ IDEA (Community đủ dùng) — tự nhận diện Maven multi-module.
- **Git** đã cài và đã có tài khoản GitHub, đã được thêm vào repo của nhóm.

Kiểm tra nhanh mọi thứ ổn: chạy `mvn clean install` ở thư mục gốc `mail-system/`
— nếu không lỗi (mấy warning là bình thường) là đã sẵn sàng.

## 1. Cấu trúc project

```
mail-system/               (Maven parent - pom.xml)
├── mail-common/            Protocol + Model dùng chung (Server & Client đều phụ thuộc)
├── mail-server/            Server độc lập (Socket + JDBC + business logic)
└── mail-client/            JavaFX Desktop App
```

**Quy tắc quan trọng**: `mail-common` là "hợp đồng" chung — KHÔNG được sửa cấu trúc
Protocol (RequestMessage/ResponseMessage/EventMessage) hay Model một cách tùy tiện,
vì mọi thay đổi ở đây ảnh hưởng CẢ Server lẫn Client. Cần sửa thì phải báo cả nhóm trước.

## 2. Cách setup & chạy

1. Tạo database: chạy file `schema.sql` vào MySQL.
2. Sửa `mail-server/src/main/resources/db.properties` cho khớp username/password MySQL của máy bạn.
3. Build toàn bộ project (từ thư mục gốc `mail-system/`):
   ```
   mvn clean install
   ```
4. Chạy Server:
   ```
   cd mail-server
   mvn exec:java -Dexec.mainClass="com.mailsystem.server.MailServer"
   ```
5. Chạy Client (mở nhiều instance để test đa client):
   ```
   cd mail-client
   mvn javafx:run
   ```

## 3. Phân công theo khung đã dựng sẵn

| Người | Module | Việc cần làm |
|---|---|---|
| **TV2-Đạt** | `mail-server` | Implement toàn bộ DAO (JDBC theo `schema.sql`) + Service (AuthService, MailService, FolderService, GroupService) — mọi chỗ có `// TODO (TV2)` |
| **TV3-Huy** | `mail-client` (giao diện) | Hoàn thiện và bổ sung thêm mới các file `.fxml` (login, inbox, compose, mail_detail...) cho đẹp và đầy đủ, tạo thêm RegisterController, MailDetailController |
| **TV4-Quang** | `mail-client` (network) + ghép nối | Hoàn thiện `ServerConnection`, nối các Controller gọi đúng Command, xử lý EVENT real-time, test toàn bộ luồng Client ↔ Server, xử lý phần còn thiếu |

## 4. Cách TV2 tự test Server (KHÔNG cần đợi Client JavaFX xong)

Đây là bước quan trọng nhất để làm việc tuần tự không bị tắc: dùng công cụ có sẵn
`com.mailsystem.server.tools.ManualTestClient` để tự gửi request giả lập, xem
response ngay trên console.

**Cách chạy:**
1. Mở 1 terminal, chạy Server: `mvn exec:java -Dexec.mainClass="com.mailsystem.server.MailServer"`
2. Mở terminal thứ 2, chạy: `mvn exec:java -Dexec.mainClass="com.mailsystem.server.tools.ManualTestClient"`
3. Gõ `REGISTER`, Enter, gõ payload: `{"email":"a@mail.local","password":"123456","displayName":"A"}`, Enter → xem RESPONSE.
4. Gõ `LOGIN` để test đăng nhập, tương tự cho các command khác.

→ **TV2 phải test được OK bằng công cụ này trước khi coi 1 command là "xong"**, không
đợi tới lúc TV3/TV4 ráp Client thật mới biết đúng/sai.

## 5. Quy trình Git (bàn giao tuần tự)

- Nhánh `main`: chỉ chứa code đã được leader review & merge. **Không code trực tiếp trên `main`.**
- Mỗi người làm trên 1 nhánh riêng:
  - TV2: `feature/server`
  - TV3: `feature/client-ui`
  - TV4: `feature/integration`
- Quy trình bàn giao:
  1. Leader push khung skeleton lên `main` (đã làm ở Bước 5).
  2. TV2: `git checkout -b feature/server` từ `main` → code → commit thường xuyên
     (format gợi ý: `[server] them AuthService.login`) → `git push origin feature/server`
     → tạo Pull Request → **leader review nhanh** → merge vào `main`.
  3. TV3: `git pull origin main` (đã có code TV2) → `git checkout -b feature/client-ui`
     → làm tương tự bước 2 → merge vào `main`.
  4. TV4: `git pull origin main` (đã có code TV2 + TV3) → `git checkout -b feature/integration`
     → ráp nối, sửa lỗi phát sinh → merge vào `main`.
- **Luôn `git pull origin main` trước khi bắt đầu code mỗi buổi**, tránh code trên bản cũ.

## 6. Definition of Done (khi nào 1 việc được coi là xong)

- Mỗi method trong DAO/Service: viết xong → tự test bằng `ManualTestClient` (hoặc
  UI thật nếu đã có) → thấy đúng kết quả kỳ vọng cho **cả trường hợp thành công lẫn
  ít nhất 1 trường hợp lỗi** (VD: LOGIN sai password phải trả ERROR, không được crash
  hay trả OK nhầm) → mới commit & coi là hoàn thành.
- Không commit code còn lỗi biên dịch (`mvn compile` phải chạy sạch trước khi push).

## 7. Mốc thời gian đề xuất (tổng 2-4 tuần)

| Giai đoạn | Thời lượng gợi ý | Người phụ trách |
|---|---|---|
| Set up môi trường + xây dựng schema + protocal + mail_common | 1 ngày | Nhóm trưởng | 
| Setup môi trường + đọc hiểu skeleton | 1-2 ngày | Cả nhóm |
| TV2 hoàn thiện Server (Auth → Folder → Mail → Group) | 5 ngày | TV2 |
| TV3 hoàn thiện giao diện (dựa trên Server đã xong) | 5 ngày | TV3 |
| TV4 ráp nối + real-time + xử lý phát sinh | 5 ngày | TV4 |
| Buffer sửa lỗi + test tổng thể + chuẩn bị demo | 3 ngày | Cả nhóm |
| Viết báo cáo (song song, không đợi code xong 100%) | Xuyên suốt | Leader |


## 8. Thứ tự nên làm chi tiết ở mail-server (TV2)

1. `AccountDAO` + `AuthService` (REGISTER/LOGIN) — test được ngay bằng ManualTestClient
2. `FolderDAO` + `FolderService` (GET_FOLDERS)
3. `MailDAO` (phần getMailList/getMailDetail trước) + `MailService`
4. `MailDAO` (phần insertMail/sendMail) — phần khó nhất, làm sau khi các phần trên ổn
5. `GroupDAO` + `GroupService`

## 9. Thứ tự nên làm ở mail-client (TV3 + TV4)

1. Test `ServerConnection` kết nối được tới Server thật của TV2 (in log ra console)
2. Hoàn thiện Login → test LOGIN với DAO/Service thật
3. Hoàn thiện Inbox (danh sách thư) → test GET_MAIL_LIST
4. Hoàn thiện Compose (gửi thư) → test SEND_MAIL
5. Thêm real-time (EVENT) sau cùng, khi các luồng cơ bản đã chạy ổn định

## 10. Dùng AI generate code — lưu ý quan trọng

Khi nhờ AI viết code cho 1 file cụ thể, luôn đính kèm:

Nội dung file interface/class rỗng tương ứng (đã có sẵn TODO)
BUSINESS_RULES.md — để AI hiểu đúng quy tắc nghiệp vụ (VD: người nhận không hợp lệ thì xử lý sao, quyền Owner group, giới hạn file đính kèm...), tránh tự đoán/bịa sai
File schema.sql (nếu đang viết DAO — tầng truy vấn CSDL)
File Command.java / ProtocolUtil.java (nếu đang viết Service/Controller — tầng xử lý giao thức)

để AI sinh code đúng convention, đúng kiến trúc lẫn đúng nghiệp vụ đã thống nhất, tránh tự bịa cấu trúc hoặc quy tắc khác.