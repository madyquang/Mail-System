# Đặc tả giao thức Mail System (MSP)

Tài liệu này mô tả **chính xác** những gì đi trên dây giữa Client và Server.
Ai viết một client mới (kể cả công cụ dòng lệnh) chỉ cần đọc tài liệu này.
Phần giải thích *vì sao* thiết kế như vậy nằm ở
[NETWORK_PROGRAMMING.md](NETWORK_PROGRAMMING.md).

Mã nguồn tham chiếu: [mail-common/.../protocol](../mail-common/src/main/java/com/mailsystem/common/protocol).

---

## 1. Tầng vận chuyển

| Thuộc tính | Giá trị |
| --- | --- |
| Giao thức | TCP (IPv4/IPv6), một kết nối lâu dài cho cả phiên làm việc |
| Cổng mặc định | `5000` (`server.port`) |
| Mã hoá ký tự | **UTF-8** ở cả hai chiều |
| Đóng khung (framing) | Mỗi gói tin là **một dòng JSON**, kết thúc bằng `\n` (chấp nhận `\r\n`) |
| Độ dài frame tối đa | Client → Server: `1 048 576` ký tự (`ProtocolConstants.MAX_FRAME_CHARS`) |
| Chiều truyền | Song công (full-duplex): Server có thể gửi EVENT bất cứ lúc nào |
| Mã hoá đường truyền | **Bắt buộc TLS 1.3** (`SSLSocket`). Server dùng chứng chỉ tự ký; Client ghim dấu vân tay SHA-256 theo mô hình TOFU (xem [SECURITY.md](SECURITY.md)) |
| Chứng chỉ client (mutual TLS) | Server **xin** chứng chỉ thiết bị khi bắt tay (`tls.clientAuth`: `off` / `want` mặc định / `need`). Ở `need`, thiết bị chưa được duyệt nhận một RESPONSE `requestId = null`, `code = DEVICE_NOT_APPROVED` rồi bị đóng kết nối |
| Giới hạn tốc độ | Mỗi kết nối 30 request/giây, dồn tối đa 120; vượt mức nhận `ERROR "Bạn gửi yêu cầu quá nhanh..."` |

Một frame JSON không bao giờ chứa ký tự `\n` thô: xuống dòng trong chuỗi được
escape thành `\\n`. Frame dài quá giới hạn bị Server đọc bỏ và trả lỗi
(`requestId = null`); kết nối vẫn tiếp tục dùng được.

## 2. Ba loại gói tin

Trường `type` ở tầng ngoài cùng cho biết loại gói (`ProtocolUtil.peekType`).

### 2.1 REQUEST (Client → Server)

```json
{"type":"REQUEST","requestId":"7f3c…","command":"LOGIN","payload":{"email":"an@mail.local","password":"Matkhau-2026"}}
```

- `requestId`: chuỗi do Client sinh (UUID), **duy nhất trong một kết nối**.
- `command`: một giá trị của enum `Command` (mục 4).
- `payload`: JSON object tuỳ command, có thể `null`.

### 2.2 RESPONSE (Server → Client)

```json
{"type":"RESPONSE","requestId":"7f3c…","status":"OK","message":"","data":{…}}
{"type":"RESPONSE","requestId":"7f3c…","status":"ERROR","message":"Email hoặc mật khẩu không đúng"}
```

- Mỗi REQUEST nhận **đúng một** RESPONSE có cùng `requestId`.
- `status`: `OK` hoặc `ERROR`. Khi `ERROR`, `message` là câu tiếng Việt hiển
  thị được cho người dùng; `data` vắng mặt.
- `requestId = null` nghĩa là **thông báo cấp kết nối**, không gắn với request
  nào: frame không phải JSON, frame quá lớn, hoặc Server quá tải (ngay sau đó
  Server đóng kết nối).

### 2.3 EVENT (Server → Client, chủ động)

```json
{"type":"EVENT","eventName":"NEW_MAIL","data":{"mailId":42}}
```

EVENT có thể xen giữa các RESPONSE. Client không trả lời EVENT.

## 3. Trạng thái phiên

```
        kết nối TCP
            │
            ▼
   ┌─────────────────┐   LOGIN OK    ┌──────────────────┐
   │  CHƯA ĐĂNG NHẬP │ ────────────▶ │   ĐÃ ĐĂNG NHẬP   │◀─┐ LOGIN OK
   │ (REGISTER/LOGIN │ ◀──────────── │ (mọi command,    │──┘ (đổi tài khoản)
   │  /PING)         │    LOGOUT     │  nhận EVENT)     │
   └─────────────────┘               └──────────────────┘
            │  đóng socket / idle quá server.idleTimeoutSeconds │
            ▼                                                    ▼
                              ĐÓNG KẾT NỐI
```

- Trạng thái đăng nhập gắn với **kết nối TCP**, không có token. Mất kết nối
  nghĩa là phải đăng nhập lại.
- Ở trạng thái chưa đăng nhập, mọi command trừ `REGISTER`, `LOGIN`, `PING` trả
  `ERROR "Bạn chưa đăng nhập."`.
- Một tài khoản có thể đăng nhập trên nhiều kết nối cùng lúc; mỗi kết nối đều
  nhận EVENT của tài khoản đó.
- Server đóng kết nối không gửi frame nào trong `server.idleTimeoutSeconds`
  giây (mặc định 300). Client chính thức gửi `PING` mỗi 30 giây.

## 4. Danh sách command

Ký hiệu: 🔓 không cần đăng nhập. Mọi `data` dưới đây là trường `data` của
RESPONSE khi `status = OK`.

### 4.1 Kết nối & tài khoản

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `PING` 🔓 | không có | `{"serverTime": <epoch millis>}` |
| `REGISTER` 🔓 | `{email, password, displayName}` | `{accountId, email, displayName}` |
| `LOGIN` 🔓 | `{email, password, totpCode?}` | `{accountId, email, displayName, folders: [Folder], resumeToken}` |
| `RESUME_SESSION` 🔓 | `{resumeToken}` | như `LOGIN` (kèm `resumeToken` **mới**); token cũ hết hiệu lực |
| `LOGOUT` | không có | `null` |
| `LOGOUT_ALL` | không có | `{revokedSessions}`: thu hồi mọi phiên khác rồi đăng xuất phiên này |
| `UPDATE_PROFILE` | `{displayName}` | `{displayName}` |
| `CHANGE_PASSWORD` | `{currentPassword, newPassword}` | `{revokedSessions, resumeToken}`: mọi phiên KHÁC bị thu hồi, mọi token cũ bị huỷ |
| `GET_SETTINGS` | không có | `{shareReadReceipts, totpEnabled}` |
| `UPDATE_SETTINGS` | `{shareReadReceipts}` | `null` |
| `TOTP_SETUP` | không có | `{secret, otpauthUri}`: khoá mới, **chưa** bật |
| `TOTP_ENABLE` | `{code}` | `null`: bật khi mã khớp khoá vừa tạo |
| `TOTP_DISABLE` | `{password, code}` | `null` |

`LOGIN` sai email hoặc mật khẩu đều trả cùng một thông báo
`"Email hoặc mật khẩu không đúng"`. Sai 5 lần (theo email, hoặc nhiều lần theo
IP) thì bị khoá 15 phút: `"Đăng nhập sai quá nhiều lần. Vui lòng thử lại sau N phút."`.
Khoá đăng nhập được lưu trong bảng `login_attempt` nên khởi động lại Server
không xoá khoá.

**Xác minh 2 bước**: tài khoản đã bật TOTP mà `LOGIN` thiếu `totpCode` thì nhận
`ERROR` với `code = "TOTP_REQUIRED"` (mật khẩu đã đúng); Client hỏi mã 6 số rồi
gửi lại `LOGIN` kèm `totpCode`. Mã được chấp nhận lệch ±30 giây và **mỗi mã chỉ
dùng một lần** (Server nhớ bước thời gian đã dùng). Nhập sai mã cũng tính là
một lần đăng nhập sai.

**Tiếp tục phiên**: `resumeToken` là chuỗi ngẫu nhiên 256 bit, dùng **một lần**,
hết hạn sau `security.resumeTokenMinutes` (mặc định 720 phút). Server chỉ lưu
SHA-256 của token, trong RAM. Token bị huỷ khi `LOGOUT`, `LOGOUT_ALL`,
`CHANGE_PASSWORD` hoặc khi Server khởi động lại; khi đó `RESUME_SESSION` trả
`code = "RESUME_REJECTED"` và Client phải đăng nhập bằng mật khẩu.

`REGISTER`/`CHANGE_PASSWORD` yêu cầu mật khẩu ≥ 8 ký tự, có cả chữ và số,
không quá phổ biến, không chứa tên email. Mỗi IP đăng ký tối đa 10 tài khoản/giờ.

### 4.2 Thư mục & thư

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `GET_FOLDERS` | không có | `[Folder]` (kèm `unreadCount`) |
| `CREATE_FOLDER` | `{name}` (1–100 ký tự, không trùng, tối đa 50 thư mục) | `{folderId}` |
| `RENAME_FOLDER` | `{folderId, name}` (chỉ thư mục `CUSTOM`) | `null` |
| `DELETE_FOLDER` | `{folderId}` (chỉ thư mục `CUSTOM`) | `{movedToTrash}`: thư bên trong chuyển vào TRASH |
| `MOVE_MAIL` | `{entryIds: [..], folderId}` (thư mục của mình, không phải TRASH) | `{processed, requested}` |
| `GET_MAIL_LIST` | `{folderId, threaded?: false}` hoặc `{view: "STARRED"}` | `[MailSummary]`, mới nhất trước (STARRED: thư gắn sao ở mọi thư mục trừ TRASH). `threaded: true` gộp các thư cùng hội thoại thành một dòng (thư mới nhất đại diện) |
| `GET_THREAD` | `{threadId}` | `[MailDetail]`: mọi thư của hội thoại mà mình xem được (trừ TRASH), cũ trước mới sau; đánh dấu đã đọc từng thư |
| `GET_MAIL_DETAIL` | `{mailId, entryId?}` | `MailDetail`; tự đánh dấu **đúng bản `entryId`** là đã đọc (trừ thư trong TRASH) |
| `SEARCH_MAIL` | `{keyword?, fromFilter?, folderId?}`, cần ít nhất 1 điều kiện; bỏ `folderId` = mọi thư mục | `[MailSummary]` |
| `SUGGEST_CONTACTS` | `{query, limit?: 1..10, includeGroups?: true}` | `[Contact]`: nhóm mình là thành viên (tối đa 3) rồi tới người khớp email/tên, người đã trao đổi nhiều thư xếp trước; không gồm chính mình |
| `MARK_READ` | `{entryIds: [..], read?: true}` (`read: false` = đánh dấu chưa đọc) | `{processed, requested}` |
| `SET_STARRED` | `{entryIds: [..], starred: true\|false}` | `{processed, requested}` |
| `DELETE_MAIL` | `{entryId}` hoặc `{entryIds: [..]}` | `{processed, requested}` (chuyển vào TRASH) |
| `RESTORE_TRASH_MAIL` | `{entryId}` hoặc `{entryIds: [..]}` | `{processed, requested}` (về thư mục gốc) |
| `DELETE_TRASH_MAIL` | `{entryId}` hoặc `{entryIds: [..]}` | `{processed, requested}` (xoá vĩnh viễn) |
| `EMPTY_TRASH` | không có | số thư đã xoá (số nguyên JSON) |

`entryId` là id của "bản thư của tôi" (dòng `mail_recipient`), khác `mailId`
(nội dung thư dùng chung). Một tài khoản có thể có **hai** bản của cùng một thư
(bản `SENDER` trong Đã gửi và bản nhận trong Hộp thư đến khi tự gửi cho mình),
nên Client gửi kèm `entryId` khi mở thư. Thiếu `entryId` thì Server ưu tiên
bản nhận.

**Thao tác hàng loạt**: `entryIds` chứa 1–500 id dương, id trùng được gộp. Mỗi
bản thư được kiểm tra quyền riêng; bản không thuộc tài khoản bị bỏ qua và không
tính vào `processed`. Nếu không bản nào được xử lý, Server trả `ERROR`. Toàn bộ
lô chỉ sinh **một** EVENT.

**Hội thoại**: thư gửi kèm `inReplyTo = mailId` (thư mình xem được) nhận
`threadId` của thư đó; thư không trả lời ai có `threadId = mailId`.

**Báo đã đọc**: lần ĐẦU người nhận mở thư (`GET_MAIL_DETAIL` / `GET_THREAD`),
Server ghi `read_at` và, nếu người nhận để `shareReadReceipts = true`, đẩy
EVENT `READ_RECEIPT` tới người gửi. `MailDetail.readReceipts` (chỉ có khi người
xem là người gửi) liệt kê ai đã đọc và lúc nào.

### 4.3 Gửi thư và tệp đính kèm

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `UPLOAD_ATTACHMENT_CHUNK` | `{uploadId?, filename, sizeBytes, offset, dataBase64}` | `{uploadId, receivedBytes, complete}` |
| `SEND_MAIL` | `{to[], cc[], bcc[], subject, body, attachments: [{uploadId}], forwardAttachmentIds?: [..], draftId?}` | `{mailId}` |
| `SAVE_DRAFT` | `{draftId?, to, cc, bcc, subject, body}` (chuỗi nguyên văn) | `{draftId}` |
| `GET_DRAFTS` | không có | `[Draft]`, mới sửa trước |
| `DELETE_DRAFTS` | `{draftIds: [..]}` | `{deleted}` |
| `DOWNLOAD_ATTACHMENT` | `{attachmentId, offset}` | `Attachment` kèm `dataBase64` của 1 khối |

**Tải tệp lên (upload theo khối):**

1. Khối đầu: bỏ `uploadId`, `offset = 0`. Server kiểm tra phần mở rộng
   (`pdf docx pptx txt jpg png zip`) và `sizeBytes` (1 byte – 25 MiB), tạo tệp tạm và
   trả `uploadId`.
2. Các khối sau: gửi kèm `uploadId`, `offset` **phải bằng** `receivedBytes`
   của lần trả lời trước. Sai offset sẽ bị từ chối kèm vị trí Server đang chờ.
3. Mỗi khối chứa tối đa `524 288` byte gốc (`CHUNK_SIZE_BYTES`) trước khi mã
   hoá Base64.
4. Khi `complete = true`, đưa `{"uploadId": …}` vào `attachments` của `SEND_MAIL`.

Tệp tạm thuộc về **kết nối**: bị xoá khi đăng xuất, đổi tài khoản, mất kết
nối, hoặc khi kết nối bắt đầu tệp thứ 6 trong lúc còn 5 tệp dở dang. Mỗi
`uploadId` chỉ dùng được cho **một** lần `SEND_MAIL`, kể cả khi lần gửi đó thất bại.

Để tương thích ngược và tiện thử tay, phần tử trong `attachments` cũng có thể
là `{filename, sizeBytes, dataBase64}` (cả tệp trong một frame, nên chỉ dùng
cho tệp nhỏ).

**Người nhận:** mỗi phần tử trong `to/cc/bcc` là một email hoặc một **tên
nhóm** (không chứa `@`). Chỉ cần một người nhận không hợp lệ là cả thư bị từ
chối (all-or-nothing) với thông báo nêu rõ người nhận đó. Khi mở rộng nhóm,
**người gửi bị loại** khỏi danh sách nhận (thư đã có trong Đã gửi); nhóm không
còn ai khác ngoài người gửi sẽ bị từ chối. Ghi email của chính mình trực tiếp
vào To/CC/BCC vẫn được phép.

**Kiểm tra nội dung tệp:** khi `SEND_MAIL`, Server đọc chữ ký đầu tệp (magic
bytes): PDF phải bắt đầu `%PDF-`, PNG/JPG đúng chữ ký ảnh, DOCX/PPTX/ZIP là
ZIP, TXT phải là UTF-8 không có byte NUL. Không khớp: `"Nội dung tệp không đúng định dạng .pdf: ..."`.

**Chuyển tiếp kèm tệp:** `forwardAttachmentIds` là `attachmentId` của thư gốc mà
người gửi có quyền xem. Server sao chép tệp thành bản riêng của thư mới; tính
chung giới hạn 5 tệp / 25 MiB với tệp tải lên.

**Gửi từ thư nháp:** kèm `draftId` thì sau khi gửi thành công bản nháp bị xoá
(và mọi phiên nhận `DRAFTS_UPDATED`).

**Giới hạn nội dung:** tiêu đề ≤ 255 ký tự, nội dung ≤ 100 000 ký tự.

**Tải tệp xuống:** gửi `offset = 0`, ghi khối nhận được, rồi gửi
`offset += số byte vừa nhận` cho tới khi đủ `sizeBytes`.

### 4.4 Nhóm thư

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `CREATE_GROUP` | `{groupName}` | `{groupId}` |
| `GET_MY_GROUPS` | không có | `[MailGroupDTO]` |
| `ADD_MEMBER` | `{groupId, email}` (chỉ chủ nhóm) | `null` |
| `REMOVE_MEMBER` | `{groupId, email}` (chỉ chủ nhóm) | `null` |
| `LEAVE_GROUP` | `{groupId}` (không áp dụng cho chủ nhóm) | `null` |
| `DELETE_GROUP` | `{groupId}` (chỉ chủ nhóm) | `null` |

### 4.5 Hoàn tác gửi và hẹn giờ gửi (v3)

`SEND_MAIL` nhận thêm một trong hai trường:

| Trường | Ý nghĩa | `data` khi OK |
| --- | --- | --- |
| (không có) | gửi ngay như cũ | `{mailId}` |
| `delaySeconds: 1..30` | thư nằm ở hộp chờ gửi vài giây để người gửi **hoàn tác** | `{outboxId, sendAt, scheduled: true}` |
| `sendAt: "2026-10-23T08:00:00"` | hẹn giờ (ít nhất 10 giây tới tối đa 365 ngày sau, giờ máy chủ) | `{outboxId, sendAt, scheduled: true}` |

Người nhận, tệp đính kèm và quyền chuyển tiếp được kiểm tra **ngay lúc gọi**;
tệp được lưu luôn để không phụ thuộc phiên tải lên. Bộ điều phối của Server
mỗi giây lấy thư tới hạn bằng `SELECT ... FOR UPDATE` và giao thư trong **cùng
một giao dịch** nên dù chạy song song cũng không gửi trùng. Nếu tới giờ gửi mà
người nhận không còn hợp lệ (VD tài khoản bị xoá), thư được chuyển thành thư
nháp và người gửi nhận EVENT `SCHEDULED_FAILED`.

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `GET_SCHEDULED` | không có | `[ScheduledMail]`, sớm nhất trước |
| `CANCEL_SCHEDULED` | `{outboxId}` | `{to, cc, bcc, subject, body, inReplyTo?, draftId?, attachmentCount}`: nội dung để mở lại khung soạn thư. Lỗi nếu thư đã được gửi |
| `UPLOAD_STATUS` | `{uploadId}` | `{uploadId, receivedBytes, complete}`: để tải lên **tiếp** sau khi rớt mạng |

Phiên tải lên dở thuộc về **tài khoản** (không phải kết nối) và được giữ
`upload.expiryMinutes` phút (mặc định 30) kể từ khối cuối: Client kết nối lại,
hỏi `UPLOAD_STATUS` rồi gửi tiếp từ `receivedBytes`.

## 5. Danh sách EVENT

| `eventName` | `data` | Gửi tới | Khi nào |
| --- | --- | --- | --- |
| `NEW_MAIL` | `{mailId, senderEmail, senderName, subject}` | mọi phiên của người nhận **và** người gửi | `SEND_MAIL` thành công (Client dùng để hiện thông báo desktop) |
| `MAIL_READ_UPDATED` | `{entryId, entryIds, read}` | mọi phiên của tài khoản thao tác | `MARK_READ` |
| `MAIL_STARRED` | `{entryId, entryIds, starred}` | mọi phiên của tài khoản thao tác | `SET_STARRED` |
| `MAIL_DELETED` | `{entryId, entryIds}` hoặc `{}` | mọi phiên của tài khoản thao tác | `DELETE_MAIL`, `DELETE_TRASH_MAIL`, `EMPTY_TRASH` |
| `MAIL_RESTORED` | `{entryId, entryIds}` | mọi phiên của tài khoản thao tác | `RESTORE_TRASH_MAIL` |
| `DRAFTS_UPDATED` | `{}` | mọi phiên của tài khoản | `SAVE_DRAFT`, `DELETE_DRAFTS`, gửi từ nháp |
| `PROFILE_UPDATED` | `{displayName}` | mọi phiên của tài khoản | `UPDATE_PROFILE` |
| `SESSION_REVOKED` | `{reason}` | các phiên bị thu hồi (sau đó Server đóng kết nối) | `CHANGE_PASSWORD`, `LOGOUT_ALL` |
| `MAIL_MOVED` | `{entryId, entryIds, folderId}` | mọi phiên của tài khoản thao tác | `MOVE_MAIL` |
| `FOLDERS_UPDATED` | `{}` | mọi phiên của tài khoản | `CREATE_FOLDER`, `RENAME_FOLDER`, `DELETE_FOLDER` |
| `READ_RECEIPT` | `{mailId, subject, readerEmail, readerName, readAt}` | mọi phiên của **người gửi** | người nhận mở thư lần đầu (và cho phép báo đã đọc) |
| `SCHEDULED_UPDATED` | `{}` | mọi phiên của người gửi | thư chờ gửi được thêm / huỷ / đã gửi |
| `SCHEDULED_FAILED` | `{outboxId, subject, reason}` | mọi phiên của người gửi | thư hẹn giờ không gửi được (đã chuyển thành nháp) |

EVENT chỉ là **tín hiệu**: Client nhận EVENT thì tải lại dữ liệu bằng
`GET_MAIL_LIST`/`SEARCH_MAIL`. Phiên đang offline không nhận EVENT, nhưng lần
đăng nhập sau vẫn thấy dữ liệu mới vì dữ liệu nằm trong DB.

## 6. Kiểu dữ liệu

```text
Folder       {folderId, folderName, folderType: INBOX|SENT|TRASH|CUSTOM, unreadCount}
MailSummary  {mailId, entryId, senderEmail, senderName, subject, snippet, sentAt,
              read, starred, hasAttachments, folderType,
              threadId, threadCount, threadEntryIds: [entryId]}     // 3 trường cuối: v3
MailDetail   {mailId, entryId, threadId, starred, folderType, senderEmail, senderName, subject, body, sentAt,
              to: [email], cc: [email], bcc: [email], attachments: [Attachment],
              readReceipts?: [ReadReceipt]}                         // chỉ khi người xem là người gửi
ReadReceipt  {email, displayName, readAt}
ScheduledMail {outboxId, to, subject, sendAt, attachmentCount}
AccountSettings {shareReadReceipts, totpEnabled}
Draft        {draftId, to, cc, bcc, subject, body, updatedAt}
Contact      {kind: PERSON|GROUP, email, displayName, interactions}  hoặc  {kind: GROUP, groupName, memberCount}
Attachment   {attachmentId, filename, mimeType, sizeBytes, dataBase64?}
MailGroupDTO {groupId, groupName, ownerEmail, owner: bool, memberEmails: [email]}
```

`sentAt` theo định dạng ISO-8601 không múi giờ (`2026-10-08T09:15:00`).
Với danh sách gộp hội thoại, thao tác hàng loạt trên một dòng nên gửi **mọi**
`threadEntryIds` của dòng đó.

### Mã lỗi máy đọc được (`ResponseMessage.code`, v3)

RESPONSE lỗi có thể kèm trường `code` để Client phản ứng mà không phải so sánh
câu chữ (câu chữ có thể đổi, mã thì không):

| `code` | Ý nghĩa | Client nên |
| --- | --- | --- |
| `TOTP_REQUIRED` | mật khẩu đúng, cần thêm mã xác minh 2 bước | hiện ô nhập mã rồi gửi lại `LOGIN` |
| `RESUME_REJECTED` | token tiếp tục phiên không còn hợp lệ | về màn hình đăng nhập |
| `DEVICE_NOT_APPROVED` | Server ở chế độ `need`, thiết bị chưa được duyệt | hiện dấu vân tay thiết bị để gửi quản trị viên |
| `RATE_LIMITED` | gửi yêu cầu quá nhanh | chờ rồi thử lại |
`bcc` chỉ có dữ liệu khi người xem là người gửi, hoặc là chính người nhận BCC đó.

## 7. Ví dụ phiên làm việc hoàn chỉnh

```text
C→S {"type":"REQUEST","requestId":"1","command":"LOGIN","payload":{"email":"an@mail.local","password":"Matkhau-2026"}}
S→C {"type":"RESPONSE","requestId":"1","status":"OK","message":"","data":{"accountId":1,…,"folders":[…]}}
C→S {"type":"REQUEST","requestId":"2","command":"UPLOAD_ATTACHMENT_CHUNK","payload":{"filename":"bao-cao.pdf","sizeBytes":700000,"offset":0,"dataBase64":"JVBERi0x…"}}
S→C {"type":"RESPONSE","requestId":"2","status":"OK","message":"","data":{"uploadId":"9b1e…","receivedBytes":524288,"complete":false}}
C→S {"type":"REQUEST","requestId":"3","command":"UPLOAD_ATTACHMENT_CHUNK","payload":{"uploadId":"9b1e…","filename":"bao-cao.pdf","sizeBytes":700000,"offset":524288,"dataBase64":"…"}}
S→C {"type":"RESPONSE","requestId":"3","status":"OK","message":"","data":{"uploadId":"9b1e…","receivedBytes":700000,"complete":true}}
C→S {"type":"REQUEST","requestId":"4","command":"SEND_MAIL","payload":{"to":["binh@mail.local"],"subject":"Báo cáo","body":"…","attachments":[{"uploadId":"9b1e…"}]}}
S→C {"type":"EVENT","eventName":"NEW_MAIL","data":{"mailId":42}}       ← EVENT có thể tới trước RESPONSE
S→C {"type":"RESPONSE","requestId":"4","status":"OK","message":"","data":{"mailId":42}}
```

(Đồng thời, kết nối của `binh@mail.local` nhận
`{"type":"EVENT","eventName":"NEW_MAIL","data":{"mailId":42}}`.)

## 8. Quy tắc cho người viết Client

0. Kết nối bằng TLS 1.3 và **kiểm tra chứng chỉ** (ghim dấu vân tay hoặc tin
   chứng chỉ cụ thể). Không bao giờ dùng TrustManager "tin tất cả" trong ứng dụng thật.
1. Đọc liên tục bằng một thread riêng; phân loại theo `type`, khớp RESPONSE
   bằng `requestId`. Đừng giả định RESPONSE tới ngay sau REQUEST.
2. Luôn đặt timeout cho mỗi request (client chính thức: 30 giây).
3. Ghi mỗi frame bằng **một** thao tác ghi có đồng bộ hoá (nhiều thread ghi
   đồng thời sẽ làm hai frame trộn lẫn byte của nhau).
4. Khi mất kết nối, coi mọi request đang chờ là thất bại và đăng nhập lại.
5. Gửi `PING` định kỳ (khoảng 30 giây) khi không có hoạt động nào khác.
6. Nhận `SESSION_REVOKED` thì xoá trạng thái đăng nhập và quay về màn hình đăng nhập.
