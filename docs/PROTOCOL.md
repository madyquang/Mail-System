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
| Mã hoá đường truyền | **Chưa có** (dữ liệu là văn bản rõ). Xem mục Bảo mật trong NETWORK_PROGRAMMING.md |

Một frame JSON không bao giờ chứa ký tự `\n` thô: xuống dòng trong chuỗi được
escape thành `\\n`. Frame dài quá giới hạn bị Server đọc bỏ và trả lỗi
(`requestId = null`); kết nối vẫn tiếp tục dùng được.

## 2. Ba loại gói tin

Trường `type` ở tầng ngoài cùng cho biết loại gói (`ProtocolUtil.peekType`).

### 2.1 REQUEST (Client → Server)

```json
{"type":"REQUEST","requestId":"7f3c…","command":"LOGIN","payload":{"email":"an@mail.local","password":"123456"}}
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
| `LOGIN` 🔓 | `{email, password}` | `{accountId, email, displayName, folders: [Folder]}` |
| `LOGOUT` | không có | `null` |

`LOGIN` sai email hoặc mật khẩu đều trả cùng một thông báo
`"Email hoặc mật khẩu không đúng"`.

### 4.2 Thư mục & thư

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `GET_FOLDERS` | không có | `[Folder]` |
| `GET_MAIL_LIST` | `{folderId}` | `[MailSummary]`, mới nhất trước |
| `GET_MAIL_DETAIL` | `{mailId}` | `MailDetail`; tự đánh dấu đã đọc (trừ thư trong TRASH) |
| `SEARCH_MAIL` | `{keyword?, fromFilter?, folderId?}`, cần ít nhất 1 điều kiện | `[MailSummary]` |
| `MARK_READ` | `{entryId}` | `null` |
| `DELETE_MAIL` | `{entryId}` | `null` (chuyển vào TRASH) |
| `RESTORE_TRASH_MAIL` | `{entryId}` | `null` (về thư mục gốc) |
| `DELETE_TRASH_MAIL` | `{entryId}` | `null` (xoá vĩnh viễn) |
| `EMPTY_TRASH` | không có | số thư đã xoá (số nguyên JSON) |

`entryId` là id của "bản thư của tôi" (dòng `mail_recipient`), khác `mailId`
(nội dung thư dùng chung).

### 4.3 Gửi thư và tệp đính kèm

| Command | Payload | `data` khi OK |
| --- | --- | --- |
| `UPLOAD_ATTACHMENT_CHUNK` | `{uploadId?, filename, sizeBytes, offset, dataBase64}` | `{uploadId, receivedBytes, complete}` |
| `SEND_MAIL` | `{to[], cc[], bcc[], subject, body, attachments: [{uploadId}]}` | `{mailId}` |
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
chối (all-or-nothing) với thông báo nêu rõ người nhận đó.

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

## 5. Danh sách EVENT

| `eventName` | `data` | Gửi tới | Khi nào |
| --- | --- | --- | --- |
| `NEW_MAIL` | `{mailId}` | mọi phiên của người nhận **và** người gửi | `SEND_MAIL` thành công |
| `MAIL_READ_UPDATED` | `{entryId}` | mọi phiên của tài khoản thao tác | `MARK_READ` |
| `MAIL_DELETED` | `{entryId}` hoặc `{}` | mọi phiên của tài khoản thao tác | `DELETE_MAIL`, `DELETE_TRASH_MAIL`, `EMPTY_TRASH` |
| `MAIL_RESTORED` | `{entryId}` | mọi phiên của tài khoản thao tác | `RESTORE_TRASH_MAIL` |

EVENT chỉ là **tín hiệu**: Client nhận EVENT thì tải lại dữ liệu bằng
`GET_MAIL_LIST`/`SEARCH_MAIL`. Phiên đang offline không nhận EVENT, nhưng lần
đăng nhập sau vẫn thấy dữ liệu mới vì dữ liệu nằm trong DB.

## 6. Kiểu dữ liệu

```text
Folder       {folderId, folderName, folderType: INBOX|SENT|TRASH|CUSTOM}
MailSummary  {mailId, entryId, senderEmail, senderName, subject, sentAt, read}
MailDetail   {mailId, senderEmail, senderName, subject, body, sentAt,
              to: [email], cc: [email], bcc: [email], attachments: [Attachment]}
Attachment   {attachmentId, filename, mimeType, sizeBytes, dataBase64?}
MailGroupDTO {groupId, groupName, ownerEmail, owner: bool, memberEmails: [email]}
```

`sentAt` theo định dạng ISO-8601 không múi giờ (`2026-10-08T09:15:00`).
`bcc` chỉ có dữ liệu khi người xem là người gửi, hoặc là chính người nhận BCC đó.

## 7. Ví dụ phiên làm việc hoàn chỉnh

```text
C→S {"type":"REQUEST","requestId":"1","command":"LOGIN","payload":{"email":"an@mail.local","password":"123456"}}
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

1. Đọc liên tục bằng một thread riêng; phân loại theo `type`, khớp RESPONSE
   bằng `requestId`. Đừng giả định RESPONSE tới ngay sau REQUEST.
2. Luôn đặt timeout cho mỗi request (client chính thức: 30 giây).
3. Ghi mỗi frame bằng **một** thao tác ghi có đồng bộ hoá (nhiều thread ghi
   đồng thời sẽ làm hai frame trộn lẫn byte của nhau).
4. Khi mất kết nối, coi mọi request đang chờ là thất bại và đăng nhập lại.
5. Gửi `PING` định kỳ (khoảng 30 giây) khi không có hoạt động nào khác.
