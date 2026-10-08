# Kiến thức Lập trình mạng trong Mail System

Tài liệu này giải thích các khái niệm lập trình mạng mà đồ án áp dụng. Mỗi
khái niệm đều chỉ ra **chỗ nó nằm trong code**, và lý do chọn cách làm đó thay
vì cách khác. Đặc tả từng gói tin nằm ở [PROTOCOL.md](PROTOCOL.md).

Mục lục

1. [Bức tranh tổng thể](#1-bức-tranh-tổng-thể)
2. [Socket TCP và vòng đời kết nối](#2-socket-tcp-và-vòng-đời-kết-nối)
3. [Đóng khung gói tin (framing)](#3-đóng-khung-gói-tin-framing)
4. [Mã hoá ký tự và dữ liệu nhị phân](#4-mã-hoá-ký-tự-và-dữ-liệu-nhị-phân)
5. [Thiết kế giao thức tầng ứng dụng](#5-thiết-kế-giao-thức-tầng-ứng-dụng)
6. [Xử lý đồng thời phía Server](#6-xử-lý-đồng-thời-phía-server)
7. [Server push: đồng bộ thời gian thực](#7-server-push-đồng-bộ-thời-gian-thực)
8. [Lập trình bất đồng bộ phía Client](#8-lập-trình-bất-đồng-bộ-phía-client)
9. [Timeout, keep-alive, heartbeat](#9-timeout-keep-alive-heartbeat)
10. [Truyền tệp lớn theo khối](#10-truyền-tệp-lớn-theo-khối)
11. [Xử lý lỗi và độ bền](#11-xử-lý-lỗi-và-độ-bền)
12. [Bảo mật mạng](#12-bảo-mật-mạng)
13. [Quan sát và kiểm thử](#13-quan-sát-và-kiểm-thử)
14. [Câu hỏi ôn tập](#14-câu-hỏi-ôn-tập)
15. [Hướng phát triển](#15-hướng-phát-triển)

---

## 1. Bức tranh tổng thể

```text
 ┌──────────── Client (JavaFX) ────────────┐            ┌──────────────── Server ────────────────┐
 │ Controller ──sendRequest()──┐           │            │  MailServer: accept() ──▶ thread pool  │
 │      ▲                      ▼           │   TCP      │                    │                   │
 │ Platform.runLater   ServerConnection ───┼── 5000 ───▶│  ClientHandler (1 kết nối)             │
 │      ▲               │  ▲               │◀───────────┼─  thread đọc ─▶ Service ─▶ DAO ─▶ MySQL│
 │      └─ listener ◀───┘  └ pendingRequests│ NDJSON    │  thread ghi ◀─ hàng đợi gửi ◀─┐        │
 │         thread             (requestId)  │            │                               │        │
 └─────────────────────────────────────────┘            │  SessionManager (accountId→phiên)──┘    │
                                                        └────────────────────────────────────────┘
```

| Khái niệm | Code |
| --- | --- |
| ServerSocket, `accept()`, giới hạn kết nối | [MailServer.java](../mail-server/src/main/java/com/mailsystem/server/MailServer.java) |
| Một kết nối = thread đọc + thread ghi | [ClientHandler.java](../mail-server/src/main/java/com/mailsystem/server/ClientHandler.java) |
| Framing có giới hạn kích thước | [FrameReader.java](../mail-common/src/main/java/com/mailsystem/common/protocol/FrameReader.java) |
| Định dạng gói tin | [RequestMessage](../mail-common/src/main/java/com/mailsystem/common/protocol/RequestMessage.java), [ResponseMessage](../mail-common/src/main/java/com/mailsystem/common/protocol/ResponseMessage.java), [EventMessage](../mail-common/src/main/java/com/mailsystem/common/protocol/EventMessage.java) |
| Server push nhiều thiết bị | [SessionManager.java](../mail-server/src/main/java/com/mailsystem/server/session/SessionManager.java) |
| Client bất đồng bộ, timeout, heartbeat | [ServerConnection.java](../mail-client/src/main/java/com/mailsystem/client/network/ServerConnection.java) |
| Upload theo khối | [UploadManager.java](../mail-server/src/main/java/com/mailsystem/server/transfer/UploadManager.java), [ComposeController.java](../mail-client/src/main/java/com/mailsystem/client/controller/ComposeController.java) |
| Download theo khối | [MailDetailController.java](../mail-client/src/main/java/com/mailsystem/client/controller/MailDetailController.java), `JdbcMailDAO.getAttachmentChunk` |
| Hằng số dùng chung hai phía | [ProtocolConstants.java](../mail-common/src/main/java/com/mailsystem/common/protocol/ProtocolConstants.java) |

Ba module tách biệt có chủ đích: `mail-common` là **hợp đồng giao thức**. Cả
hai phía biên dịch từ cùng một mã nguồn, nên tên command, cấu trúc gói tin và
các giới hạn không thể lệch nhau.

---

## 2. Socket TCP và vòng đời kết nối

### 2.1 Vì sao TCP mà không phải UDP

| Yêu cầu của ứng dụng mail | TCP | UDP |
| --- | --- | --- |
| Không được mất thư, mất khối tệp | Có: tự truyền lại gói bị mất | Không: ứng dụng phải tự làm |
| Đúng thứ tự (khối tệp 1 trước khối 2) | Có | Không |
| Phiên làm việc dài, có trạng thái đăng nhập | Có: có khái niệm kết nối | Không có kết nối |
| Độ trễ cực thấp, chấp nhận mất dữ liệu (game, video) | Không phù hợp | Phù hợp |

Mail cần **tin cậy và đúng thứ tự** hơn là độ trễ thấp nhất, nên chọn TCP.

### 2.2 Socket là gì

Một kết nối TCP được xác định bởi bộ 4 giá trị:
`(IP client, cổng client, IP server, cổng server)`.

- `ServerSocket` (phía Server) chỉ **lắng nghe** ở cổng 5000. Nó không trao
  đổi dữ liệu với ai.
- Mỗi lần `serverSocket.accept()` trả về một `Socket` **mới** đại diện cho
  một kết nối cụ thể. 100 client tức là 100 đối tượng `Socket` trên Server,
  tất cả cùng cổng 5000 nhưng khác `(IP, cổng)` phía client.
- Cổng phía client là **cổng tạm (ephemeral port)** do hệ điều hành cấp, VD
  `54213`. `ManualTestClient` in ra cổng này để quan sát.

### 2.3 Vòng đời một kết nối trong dự án

```text
Client                                         Server
  │                                              │ bind(5000), listen(backlog=50)
  │ connect(host, 5000, timeout 5s)              │
  │ ─── SYN ───────────────────────────────────▶ │
  │ ◀── SYN-ACK ──────────────────────────────── │   bắt tay 3 bước (do HĐH làm)
  │ ─── ACK ───────────────────────────────────▶ │   kết nối vào hàng đợi backlog
  │                                              │ accept() trả về Socket mới
  │                                              │ setTcpNoDelay, setKeepAlive, setSoTimeout
  │ ── REQUEST LOGIN ──────────────────────────▶ │
  │ ◀───────────────────────────── RESPONSE OK ─ │   SessionManager.register()
  │ ── REQUEST … / PING mỗi 30s ───────────────▶ │
  │ ◀──────────────────────── RESPONSE / EVENT ─ │
  │ socket.close()                               │
  │ ─── FIN ───────────────────────────────────▶ │ readFrame() trả null
  │                                              │ gửi nốt hàng đợi, unregister, đóng socket
```

Các điểm cần chú ý trong code:

- **Backlog** (`MailServer.BACKLOG = 50`): số kết nối đã bắt tay xong đang chờ
  `accept()`. Vì vòng `accept()` chỉ giao việc cho thread pool rồi quay lại
  ngay, hàng đợi này hầu như luôn trống.
- **Connect timeout**: `socket.connect(address, 5000)` trong
  `ServerConnection.connect`. Không đặt timeout thì khi máy chủ không tồn tại
  trong LAN, lệnh này có thể treo hàng chục giây (HĐH gửi lại SYN nhiều lần).
- **Đóng kết nối**: `readFrame()` trả `null` khi đọc được EOF, tức là bên kia
  đã gửi FIN. Đây là cách *bình thường* để biết client thoát. Ngược lại,
  `IOException` ("Connection reset") nghĩa là kết nối bị huỷ đột ngột (RST).
- **SO_REUSEADDR** (`setReuseAddress(true)` trước `bind`): trên Linux/macOS,
  cho phép khởi động lại Server ngay dù cổng còn kết nối cũ ở trạng thái
  `TIME_WAIT`. Phải gọi *trước* khi `bind`, nên Server tạo `new ServerSocket()`
  rỗng rồi mới `bind`.

---

## 3. Đóng khung gói tin (framing)

### 3.1 Vấn đề: TCP là luồng byte

TCP đảm bảo các byte tới **đủ và đúng thứ tự**, nhưng **không giữ ranh giới**
giữa các lần gửi:

```text
Bên gửi:  write("{…LOGIN…}\n")   write("{…GET_FOLDERS…}\n")

Bên nhận có thể đọc được:
  read() #1: "{…LOGIN…}\n{…GET_FO"      ← dính gói (2 gói trong 1 lần đọc)
  read() #2: "LDERS…}\n"                ← tách gói (1 gói qua 2 lần đọc)
```

Vì vậy **giao thức tầng ứng dụng phải tự đánh dấu chỗ kết thúc mỗi gói**. Đây
là một trong những lỗi phổ biến nhất khi mới lập trình socket: chương trình
chạy đúng trên localhost (gói nhỏ, mạng nhanh) nhưng hỏng khi chạy qua mạng
thật.

### 3.2 Các cách framing

| Cách | Ví dụ | Ưu | Nhược |
| --- | --- | --- | --- |
| Ký tự phân cách | `\n` sau mỗi JSON (dự án này), SMTP dùng `\r\n` | Dễ đọc, gõ tay được bằng `ncat` | Dữ liệu không được chứa ký tự phân cách |
| Tiền tố độ dài | 4 byte độ dài + nội dung (HTTP/2, gRPC) | Hiệu quả, nhị phân an toàn | Khó quan sát bằng mắt |
| Độ dài cố định | Mọi gói 512 byte | Đơn giản nhất | Lãng phí, không linh hoạt |

Dự án dùng **JSON phân cách bằng dòng** (newline-delimited JSON). Cách này an
toàn vì Gson không bao giờ sinh `\n` thô bên trong một JSON: ký tự xuống dòng
trong nội dung thư được escape thành `\\n`. Test
`FrameReaderTest.gsonNeverEmitsRawNewlineInsideFrame` khẳng định điều này.

### 3.3 Giới hạn kích thước frame

`BufferedReader.readLine()` đọc tới khi gặp `\n`, **không có giới hạn**. Một
client độc hại chỉ cần gửi 2GB không có `\n` là Server hết bộ nhớ. Đây là một
dạng tấn công từ chối dịch vụ (DoS).

[FrameReader](../mail-common/src/main/java/com/mailsystem/common/protocol/FrameReader.java)
thay thế `readLine()`:

- Đọc vào bộ đệm 8 KB, cắt theo `\n`, chấp nhận `\r\n`.
- Frame vượt `MAX_FRAME_CHARS` (1 MiB ký tự): **bỏ phần đã đọc khỏi bộ nhớ**,
  tiếp tục đọc bỏ tới `\n` rồi ném `FrameTooLargeException`. Nhờ vậy luồng vẫn
  **đồng bộ lại** được: frame kế tiếp được đọc đúng, Server trả lỗi rồi phục
  vụ tiếp thay vì phải đóng kết nối.
- Kết nối đóng giữa chừng một frame: phần dở dang bị bỏ, vì một frame chưa có
  `\n` là frame chưa hoàn chỉnh.

---

## 4. Mã hoá ký tự và dữ liệu nhị phân

**Luôn chỉ định UTF-8 ở cả hai đầu.** `new InputStreamReader(in)` không chỉ
định charset sẽ dùng charset mặc định của máy. Trên Windows cũ đó là `Cp1252`,
khiến chữ "Thư mới" thành "ThÆ° má»›i" khi Server và Client khác cấu hình. Mọi
reader/writer trong dự án đều truyền `StandardCharsets.UTF_8`.

**Dữ liệu nhị phân trong giao thức văn bản**: tệp PDF/ảnh chứa byte bất kỳ,
kể cả `\n` và byte không hợp lệ UTF-8, nên không thể nhét thẳng vào JSON. Dự án
mã hoá **Base64**: 3 byte thành 4 ký tự ASCII an toàn. Cái giá là dung lượng
tăng khoảng **33%**. Một khối 512 KB thành khoảng 683 KB ký tự, vẫn nằm trong giới
hạn 1 MiB của frame.

---

## 5. Thiết kế giao thức tầng ứng dụng

### 5.1 Ba loại gói tin

| Loại | Chiều | Vai trò |
| --- | --- | --- |
| `REQUEST` | C → S | Yêu cầu, mang `requestId` |
| `RESPONSE` | S → C | Trả lời đúng một REQUEST (cùng `requestId`) |
| `EVENT` | S → C | Server **chủ động** báo, không ai hỏi |

Có `EVENT` nên đây không phải giao thức hỏi–đáp thuần như HTTP/1.1. Trên cùng
một kết nối, dữ liệu có thể chảy theo cả hai chiều bất cứ lúc nào (song công).

### 5.2 `requestId`: ghép cặp request với response

Vì EVENT có thể chen vào bất cứ lúc nào, Client **không thể** dùng kiểu "gửi
rồi đọc dòng kế tiếp là câu trả lời". Mỗi request mang một UUID. Client lưu
`requestId → CompletableFuture` trong `pendingRequests`, và khi RESPONSE về thì
tra đúng future để hoàn tất. Hệ quả:

- **Pipelining**: Client có thể gửi nhiều request liên tiếp mà không chờ. Test
  `ServerNetworkTest.pipelinedRequestsAreAnsweredInOrderOnOneConnection` gửi 3
  request trong một lần ghi.
- Client không phụ thuộc thứ tự response. Test
  `ServerConnectionTest.matchesResponsesByRequestIdEvenWhenOutOfOrderAndDeliversEvents`
  trả lời ngược thứ tự và chèn EVENT ở giữa.

### 5.3 Giao thức có trạng thái (stateful)

Trạng thái đăng nhập lưu trong `ClientHandler.loggedInAccountId`, **gắn với
kết nối TCP**. Request sau không cần gửi lại mật khẩu hay token.

| | Stateful theo kết nối (dự án) | Stateless + token (REST/HTTP) |
| --- | --- | --- |
| Xác thực | Một lần lúc LOGIN | Mỗi request gửi token |
| Mất kết nối | Phải đăng nhập lại | Token vẫn dùng được |
| Server push | Tự nhiên: biết kết nối nào của ai | Cần thêm cơ chế (WebSocket, SSE) |

Phân quyền tập trung: `ClientHandler.PUBLIC_COMMANDS` liệt kê các command
không cần đăng nhập (`REGISTER`, `LOGIN`, `PING`), còn lại bị chặn ngay ở tầng
mạng trước khi tới Service.

### 5.4 So sánh với giao thức mail thật

| | Mail System | SMTP / IMAP |
| --- | --- | --- |
| Định dạng | JSON, mỗi dòng 1 gói | Lệnh văn bản (`MAIL FROM:`, `A1 FETCH`) kết thúc `\r\n` |
| Ghép cặp | `requestId` | IMAP dùng *tag* (`A1`, `A2`) đúng như `requestId` |
| Push | EVENT | IMAP `IDLE` (server báo thư mới) |
| Cổng | 5000 | SMTP 25/587, IMAP 143/993 |

Thiết kế của dự án đi theo cùng ý tưởng với IMAP, chỉ đổi cú pháp sang JSON.

---

## 6. Xử lý đồng thời phía Server

### 6.1 Mô hình thread-per-connection

`accept()`, `read()`, `write()` đều là **I/O chặn** (blocking): thread đứng
chờ tới khi có dữ liệu. Nếu một thread phục vụ mọi client, client A đang im
lặng sẽ chặn luôn client B. Vì vậy mỗi kết nối được phục vụ bởi thread riêng:

```java
// MailServer.handleNewConnection
if (!connectionSlots.tryAcquire()) { rejectBusy(client); return; }
clientThreads.execute(new ClientHandler(client, onClose));
```

- **Thread pool** (`Executors.newCachedThreadPool`) tái sử dụng thread đã kết
  thúc thay vì `new Thread()` mỗi lần.
- **Semaphore** giới hạn `server.maxClients` kết nối đồng thời. Kết nối vượt
  mức **không bị treo**: Server gửi một frame lỗi "Máy chủ đang quá tải" rồi
  đóng, nên Client hiển thị được lý do.

| Mô hình | Thread | Phù hợp |
| --- | --- | --- |
| Thread-per-connection + blocking I/O (dự án) | 2 / kết nối | Vài trăm kết nối, code dễ hiểu |
| NIO `Selector` (non-blocking, event loop) | Vài thread cho hàng nghìn kết nối | Server lớn (Netty, Nginx) |
| Virtual threads (Java 21+) | Hàng triệu thread "nhẹ" | Viết như blocking, chi phí như NIO |

### 6.2 Vì sao mỗi kết nối có thêm một thread ghi

EVENT được gửi **từ thread của client khác**: A gửi thư thì thread của A gọi
`pushEvent(B, NEW_MAIL)`. Nếu thread của A ghi thẳng vào socket của B:

- Mạng của B chậm thì bộ đệm gửi TCP đầy, `write()` **chặn**, và thread của A
  bị treo theo. Một client tồi kéo chậm người khác.
- Hai thread cùng ghi một socket: hai frame có thể **trộn byte** vào nhau.

Giải pháp trong [ClientHandler](../mail-server/src/main/java/com/mailsystem/server/ClientHandler.java):

```text
thread đọc (RESPONSE) ─┐
thread client khác ────┼─▶ outbound: BlockingQueue (tối đa 256 frame) ─▶ thread ghi ─▶ socket
 (EVENT qua SessionManager)
```

- `enqueue()` chỉ `offer()` vào hàng đợi rồi trả về ngay, **không bao giờ
  chặn**.
- Chỉ **một** thread ghi socket nên frame không bao giờ trộn lẫn.
- Hàng đợi đầy nghĩa là client không đọc kịp (slow consumer). Server **đóng
  kết nối đó** thay vì để bộ nhớ phình mãi. Đây là một dạng
  **backpressure**.
- Thread ghi gộp nhiều frame đang chờ vào một lần `flush()`, nên số gói TCP ít hơn.

### 6.3 Dữ liệu dùng chung giữa các thread

| Dữ liệu | Cách bảo vệ |
| --- | --- |
| `SessionManager.onlineSessions` | `ConcurrentHashMap` + `compute()` nguyên tử |
| `ClientHandler.loggedInAccountId` | `volatile` (ghi ở thread đọc, đọc ở thread khác khi đóng) |
| `ClientHandler.closed` | `AtomicBoolean.compareAndSet`: `close()` chỉ chạy một lần dù được gọi từ hai thread |
| `UploadManager` | method `synchronized` |
| Ghi socket phía Client | khối `synchronized (lock)` quanh `writeFrame` |

Ví dụ một race condition đã sửa: phiên bản cũ dùng
`Map<accountId, ClientHandler>` và `unregister(accountId)`. Khi một tài khoản
đăng nhập trên 2 máy, máy thứ hai ghi đè máy thứ nhất. Khi máy thứ nhất thoát,
lệnh `unregister` xoá luôn đăng ký của máy thứ hai, nên **cả hai đều không
nhận EVENT**. Bản mới lưu `Set<ClientSession>` và chỉ gỡ đúng phiên đóng
(`SessionManagerTest.disconnectingOneDeviceKeepsTheOtherOnline`).

---

## 7. Server push: đồng bộ thời gian thực

### 7.1 Các cách để Client biết có thư mới

| Cách | Hoạt động | Nhược điểm |
| --- | --- | --- |
| Polling | Client hỏi "có gì mới?" mỗi N giây | Trễ tới N giây, tốn băng thông khi không có gì |
| Long polling | Server giữ request tới khi có dữ liệu | Phức tạp, mỗi lần trả lời phải mở request mới |
| **Server push trên kết nối lâu dài** (dự án) | Server ghi EVENT vào kết nối sẵn có | Phải giữ kết nối mở và quản lý phiên |
| WebSocket | Như trên nhưng chạy trên HTTP | Cần nâng cấp từ HTTP |

Kết nối TCP của dự án vốn đã mở suốt phiên làm việc, nên push gần như miễn
phí: chỉ cần biết kết nối nào thuộc tài khoản nào. Đó là việc của
`SessionManager`.

### 7.2 Luồng một EVENT

```text
Client A (gửi)        ClientHandler A          SessionManager        ClientHandler B       Client B
    │ SEND_MAIL ─────────▶ │                          │                     │                  │
    │                      │ MailService lưu DB       │                     │                  │
    │                      │ pushEvent(B, NEW_MAIL) ─▶│ tìm mọi phiên của B │                  │
    │                      │                          │ sendEvent() ───────▶│ offer vào hàng đợi│
    │ ◀── RESPONSE OK ──── │                          │                     │ thread ghi ─────▶│ listener thread
    │                      │                          │                     │                  │ Platform.runLater
    │                      │                          │                     │                  │ → GET_MAIL_LIST
```

- EVENT là **tín hiệu**, không mang toàn bộ dữ liệu. Client nhận rồi tự tải
  lại danh sách. Cách này đơn giản và luôn đúng, kể cả khi bỏ lỡ một EVENT.
- Tài khoản offline không nhận EVENT, nhưng không mất gì: dữ liệu nằm trong DB.
- Push xảy ra **sau khi transaction commit**. Nếu push trước commit, Client có
  thể tải lại khi dữ liệu chưa có.

---

## 8. Lập trình bất đồng bộ phía Client

JavaFX có **một** UI thread. Nếu UI thread chờ mạng (`readLine()`, `connect()`),
cửa sổ đứng hình. [ServerConnection](../mail-client/src/main/java/com/mailsystem/client/network/ServerConnection.java)
dùng 3 loại thread:

| Thread | Việc |
| --- | --- |
| JavaFX UI thread | Gọi `sendRequest()` (chỉ ghi frame rồi trả future ngay), cập nhật giao diện |
| `mail-listener` | `readFrame()` liên tục, hoàn tất future hoặc gọi `eventListener` |
| `mail-heartbeat` | Gửi PING định kỳ |

```java
// Mẫu dùng trong Controller
ServerConnection.getInstance().sendRequest(Command.GET_MAIL_LIST, payload)
        .whenComplete((response, error) -> Platform.runLater(() -> {
            // chạy trên UI thread: được phép sửa giao diện
        }));
```

Chi tiết quan trọng:

- **Đăng ký future trước khi ghi**: `pendingRequests.put()` đứng trước
  `writeFrame()`. Server trên localhost có thể trả lời nhanh tới mức RESPONSE
  về trước khi `write()` trả về. Nếu đăng ký sau, response đó sẽ không tìm thấy
  future.
- **Mọi request đều có timeout** (`orTimeout(30s)`), không request nào có thể
  treo UI mãi mãi.
- `Platform.runLater` bắt buộc: chạm vào control JavaFX từ thread khác sẽ ném
  `IllegalStateException` hoặc gây lỗi khó tái hiện.
- Kết nối mở **trên thread nền** (`ensureConnectedAsync`) khi người dùng bấm
  Đăng nhập. App vẫn mở được khi Server chưa chạy; địa chỉ máy chủ nhập ở màn
  hình đăng nhập (`host:port`) để thử qua LAN.
- Đọc tệp đính kèm ở executor riêng (`FILE_IO`), không đọc trên thread listener.
  Nếu thread listener bị chặn bởi đĩa chậm thì không ai đọc socket, và mọi
  response khác cũng bị kẹt.

---

## 9. Timeout, keep-alive, heartbeat

Một kết nối TCP có thể **chết mà không ai biết** (half-open). Ví dụ: rút dây
mạng, Wi-Fi rớt, máy kia mất điện. Khi đó không có gói FIN/RST nào được gửi, và
`read()` sẽ chờ mãi mãi. Dự án dùng nhiều lớp bảo vệ:

| Cơ chế | Ở đâu | Giá trị | Phát hiện |
| --- | --- | --- | --- |
| Connect timeout | Client `socket.connect(…, 5000)` | 5 s | Máy chủ không tồn tại hoặc bị chặn |
| Request timeout | Client `orTimeout` | 30 s | Server treo, không trả lời |
| Heartbeat `PING` | Client, mỗi 30 s, chờ 10 s | 30 s / 10 s | Kết nối chết nhìn từ phía Client |
| `SO_TIMEOUT` | Server `setSoTimeout` | 300 s | Client im lặng quá lâu (kết nối chết nhìn từ phía Server) |
| TCP keep-alive | Cả hai `setKeepAlive(true)` | Do HĐH, thường 2 giờ | Lớp cuối cùng, rất chậm |
| JDBC timeout | `connectTimeout`, `socketTimeout`, `DriverManager.setLoginTimeout` | 5 s / 30 s | MySQL không phản hồi |

Vì sao cần heartbeat ở tầng ứng dụng khi TCP đã có keep-alive: keep-alive mặc
định chỉ bắt đầu thăm dò sau **2 giờ** im lặng và không chỉnh được theo từng
socket bằng API Java chuẩn. PING mỗi 30 giây giúp phát hiện trong vòng chưa
tới một phút. PING cũng giữ cho Server không đóng kết nối vì `SO_TIMEOUT`.

**TCP_NODELAY**: thuật toán Nagle gom các gói nhỏ lại để gửi một lần. Khi kết hợp với
*delayed ACK* của bên nhận, nó có thể làm mỗi lượt hỏi–đáp nhỏ trễ thêm hàng chục
tới hàng trăm mili giây. Giao thức này gồm nhiều frame JSON nhỏ, mang tính tương
tác, nên cả hai phía tắt Nagle bằng `setTcpNoDelay(true)`. Thay vào đó, thread
ghi tự gộp các frame đang chờ trước khi `flush()`.

---

## 10. Truyền tệp lớn theo khối

### 10.1 Vấn đề của "một frame chứa cả tệp"

Phiên bản trước gửi tệp 25 MB thành một chuỗi Base64 khoảng 33 MB trong `SEND_MAIL`:

- Cả Client lẫn Server phải giữ toàn bộ chuỗi trong RAM, cộng thêm mảng byte
  sau khi giải mã. 10 người gửi cùng lúc có thể ngốn hàng GB.
- Trong lúc truyền 33 MB, kết nối bị **chiếm trọn**: EVENT và response khác phải
  xếp hàng chờ.
- Không hiển thị được tiến độ, không giới hạn được kích thước frame.

### 10.2 Giải pháp: chia khối + định danh phiên tải lên

```text
Client                                              Server (UploadManager của kết nối)
  │ UPLOAD_ATTACHMENT_CHUNK offset=0, 512KB ──────▶ │ kiểm tra đuôi/size, tạo tệp tạm, uploadId=U
  │ ◀──────────────────── {U, receivedBytes=524288} │
  │ UPLOAD_ATTACHMENT_CHUNK U, offset=524288 ─────▶ │ offset == đã nhận? ghi nối tiếp
  │ ◀────────── {U, receivedBytes=700000, complete} │
  │ SEND_MAIL attachments=[{uploadId:U}] ─────────▶ │ take(U), kiểm tra lại, move tệp vào kho
  │ ◀─────────────────────────────── {mailId: 42}   │ trong cùng transaction lưu thư
```

Các khái niệm mạng áp dụng ở đây:

- **Stop-and-wait**: gửi một khối, chờ xác nhận rồi mới gửi khối tiếp. Đơn giản,
  bộ nhớ ổn định; nhược điểm là mỗi khối tốn một vòng RTT. Cải tiến là
  *sliding window*: gửi trước N khối chưa được xác nhận, giống cách chính TCP
  làm bên dưới.
- **Offset như số xác nhận (ACK)**: Server trả `receivedBytes`, Client tiếp tục
  từ đúng vị trí đó. Khối gửi lặp hoặc sai thứ tự bị từ chối kèm vị trí Server
  đang chờ. TCP đã đảm bảo thứ tự trong một kết nối, nhưng kiểm tra ở tầng ứng
  dụng chặn được lỗi logic của client và là nền tảng để **tải tiếp sau khi rớt
  mạng** (resume).
- **Kích thước khối** (512 KB): khối nhỏ thì nhiều RTT; khối lớn thì tốn RAM và
  frame quá to. 512 KB sau Base64 vẫn dưới giới hạn frame 1 MiB.
- **Server không tin Client**: kích thước khai báo, kích thước thực trên đĩa,
  đuôi tệp, số tệp và tổng dung lượng đều được kiểm tra lại khi `SEND_MAIL`.
- **Dọn dẹp theo vòng đời kết nối**: tệp tạm thuộc về kết nối. Đóng kết nối,
  đăng xuất hay đổi tài khoản đều xoá chúng. Server khởi động thì xoá tệp `.part`
  sót lại từ lần chạy trước.

Chiều **tải xuống** dùng cùng ý tưởng (`DOWNLOAD_ATTACHMENT` với `offset`).
Client ghi từng khối vào tệp `.part` và chỉ đổi tên thành tệp thật
(`ATOMIC_MOVE`) sau khi đủ kích thước. Người dùng không bao giờ thấy một tệp
tải dở.

---

## 11. Xử lý lỗi và độ bền

| Tình huống | Xử lý |
| --- | --- |
| Frame không phải JSON / thiếu `type` / command lạ | RESPONSE `ERROR`, **giữ kết nối** |
| Frame quá lớn | Đọc bỏ tới `\n`, RESPONSE `ERROR` với `requestId = null`, giữ kết nối |
| Exception bất ngờ trong Service | Bắt ở `ClientHandler.handleFrame`, ghi log, trả `ERROR` chung chung, không lộ stack trace |
| Server quá tải | Frame lỗi rồi đóng; Client hiển thị đúng thông báo đó |
| Server tắt / rớt mạng | Client: mọi request đang chờ thất bại ngay với `ConnectionLostException`, cảnh báo, quay về màn hình đăng nhập |
| Frame hỏng từ Server | Thread listener bỏ qua frame đó, **không chết** (bản cũ chết lặng lẽ, khiến mọi request sau treo) |
| Kết nối cũ đóng trễ sau khi đã kết nối lại | Biến `generation`: thread nghe của kết nối cũ không thể đóng nhầm kết nối mới |
| Gửi thư lỗi giữa chừng | Transaction DB rollback, xoá tệp đã ghi (all-or-nothing) |
| Tắt Server (Ctrl+C) | Shutdown hook: đóng `ServerSocket`, đóng mọi kết nối, chờ thread pool tối đa 5 s |

**Đóng kết nối "lịch sự"**: `ClientHandler.close()` không đóng socket ngay mà
đặt frame đánh dấu `END_OF_STREAM` vào hàng đợi. Thread ghi gửi nốt các response
còn chờ rồi mới đóng. Đóng socket ngay có thể làm mất RESPONSE cuối cùng.

---

## 12. Bảo mật mạng

Những gì dự án **đã làm**:

- Mật khẩu lưu bằng **PBKDF2-HMAC-SHA256** (210 000 vòng, salt ngẫu nhiên 16
  byte), so sánh bằng `MessageDigest.isEqual` (thời gian hằng, chống
  *timing attack*).
- Đăng nhập sai luôn trả **cùng một thông báo**. Khi email không tồn tại,
  Server vẫn băm mật khẩu để thời gian phản hồi giống trường hợp sai mật khẩu.
  Kẻ tấn công không dò được email nào đã đăng ký (*user enumeration*).
- Kiểm tra quyền ở Server cho mọi thao tác (thư thuộc tài khoản, chủ nhóm,
  BCC chỉ hiện cho đúng người), không tin dữ liệu từ Client.
- Giới hạn tài nguyên chống DoS: kích thước frame, số kết nối, hàng đợi gửi,
  số tệp tạm mỗi kết nối, idle timeout.
- Câu lệnh SQL dùng `PreparedStatement`, chống SQL injection.
- Tên tệp từ Client bị cắt bỏ phần đường dẫn; tệp lưu trên Server dùng tên
  UUID, chống *path traversal*.

Những gì **chưa có** và cần biết khi bảo vệ đồ án:

- **Kênh truyền chưa mã hoá**: mật khẩu và nội dung thư đi qua mạng dưới dạng
  văn bản rõ. Ai bắt gói được trong cùng mạng LAN (Wireshark, ARP spoofing)
  đều đọc được. Cách khắc phục là **TLS**: thay `ServerSocket`/`Socket` bằng
  `SSLServerSocketFactory`/`SSLSocketFactory` của JSSE. Phần framing và giao
  thức JSON giữ nguyên vì TLS nằm giữa TCP và tầng ứng dụng.
- Chưa giới hạn số lần đăng nhập sai (*brute force*). Có thể đếm số lần sai
  theo IP hoặc tài khoản.

---

## 13. Quan sát và kiểm thử

### 13.1 Nhìn tận mắt giao thức

- **ManualTestClient**: có thread nền in mọi frame nhận được. Mở 2 cửa sổ,
  đăng nhập 2 tài khoản, gửi thư từ cửa sổ 1 sẽ thấy `<< EVENT : NEW_MAIL` ở
  cửa sổ 2.
- **ncat / telnet**: vì giao thức là văn bản theo dòng, có thể gõ tay:
  ```text
  ncat localhost 5000
  {"type":"REQUEST","requestId":"1","command":"PING"}
  ```
- **Wireshark** với bộ lọc `tcp.port == 5000` trên adapter *Loopback* (Npcap).
  Quan sát được: bắt tay SYN/SYN-ACK/ACK, mỗi frame JSON trong một hoặc nhiều
  segment có cờ PSH, các khối upload bị chia nhỏ theo MSS, FIN khi đóng. Đây
  cũng là cách chứng minh mật khẩu đang đi dạng văn bản rõ (mục 12).
- **netstat**: `netstat -ano | findstr :5000` liệt kê cổng LISTENING và từng kết
  nối ESTABLISHED. Sau khi đóng sẽ thấy các kết nối ở trạng thái TIME_WAIT.

### 13.2 Test tự động (`mvn test`, không cần MySQL)

| Test | Kiểm chứng |
| --- | --- |
| `FrameReaderTest` | Ghép frame bị chia nhỏ, CRLF, frame quá lớn rồi đồng bộ lại, frame dang dở |
| `ServerNetworkTest` | TCP thật: PING, pipelining, frame hỏng, frame quá lớn, bắt buộc đăng nhập, từ chối khi quá tải, giải phóng slot khi đóng |
| `SessionManagerTest` | Push tới mọi thiết bị, một thiết bị thoát không ảnh hưởng thiết bị khác |
| `UploadManagerTest` | Ghép khối, từ chối khối sai/lặp, kiểm tra đuôi và kích thước, dọn tệp tạm |
| `ServerConnectionTest` | Ghép response theo `requestId` khi về ngược thứ tự, EVENT chen giữa, mất kết nối làm hỏng request đang chờ, thông báo quá tải, connect lỗi nhanh |

### 13.3 Thử qua mạng LAN

1. Máy chạy Server: mở cổng 5000 trên tường lửa Windows (Inbound rule, TCP 5000).
2. Xem IP LAN của máy đó bằng `ipconfig`, VD `192.168.1.10`.
3. Máy khác chạy Client, nhập `192.168.1.10:5000` ở ô **Máy chủ** của màn hình
   đăng nhập.

---

## 14. Câu hỏi ôn tập

<details><summary>Vì sao không dùng <code>readLine()</code> của BufferedReader?</summary>
Không giới hạn độ dài dòng, nên một client gửi dữ liệu không có <code>\n</code> làm Server hết
bộ nhớ. <code>FrameReader</code> giới hạn 1 MiB ký tự và đồng bộ lại luồng sau frame quá lớn.
</details>

<details><summary>Server có 100 client thì có bao nhiêu thread?</summary>
Khoảng 200 (một thread đọc và một thread ghi cho mỗi kết nối), cộng thread accept.
Thread đọc lấy từ thread pool, thread ghi tạo riêng.
</details>

<details><summary>Nếu xoá <code>requestId</code> thì có vấn đề gì?</summary>
Client không phân biệt được frame nhận về là trả lời cho request nào. Khi EVENT
chen vào hoặc gửi nhiều request liên tiếp, response sẽ bị ghép nhầm.
</details>

<details><summary>Khi rút dây mạng của Client, Server biết lúc nào?</summary>
Không có FIN/RST nên Server không biết ngay. Sau <code>server.idleTimeoutSeconds</code>
(300 s) không nhận frame nào, <code>read()</code> ném <code>SocketTimeoutException</code> và Server
đóng kết nối, gỡ phiên. Phía Client phát hiện sớm hơn nhờ PING không có trả lời.
</details>

<details><summary>Vì sao EVENT chỉ báo "có thay đổi" mà không gửi kèm cả danh sách thư?</summary>
Giữ EVENT nhỏ. Client luôn tải dữ liệu mới nhất nên vẫn đúng kể cả khi bỏ lỡ EVENT,
và Server không phải biết mỗi Client đang mở thư mục nào.
</details>

<details><summary>Tại sao cần thread ghi riêng khi đã có <code>synchronized</code>?</summary>
<code>synchronized</code> chỉ tránh trộn byte; thread gọi <code>write()</code> vẫn bị chặn khi bộ đệm
TCP của client nhận đầy. Hàng đợi tách thread gửi EVENT khỏi tốc độ mạng của người nhận.
</details>

---

## 15. Hướng phát triển

| Hướng | Lợi ích | Thay đổi chính |
| --- | --- | --- |
| TLS (`SSLSocket`) | Mã hoá mật khẩu và nội dung thư | `MailServer`, `ServerConnection` |
| Java NIO / Netty hoặc virtual threads | Phục vụ hàng nghìn kết nối | `MailServer`, `ClientHandler` |
| Tự kết nối lại + gửi lại request an toàn | Không phải đăng nhập lại khi rớt mạng ngắn | Phiên có token, request idempotent |
| Sliding window khi upload | Tận dụng băng thông trên đường truyền có RTT cao | `ComposeController`, `UploadManager` |
| Resume upload/download sau khi rớt mạng | Không phải tải lại từ đầu | Giữ `uploadId` qua các kết nối |
| Connection pool JDBC (HikariCP) | Giảm thời gian mở kết nối MySQL | `DatabaseConnection` |
| Framing nhị phân (length-prefix) | Không tốn 33% cho Base64 | `FrameReader` + định dạng gói tin |
