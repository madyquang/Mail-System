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
12. [Bảo mật: TLS 1.3 và các lớp phòng thủ](#12-bảo-mật-tls-13-và-các-lớp-phòng-thủ)
13. [Quan sát và kiểm thử](#13-quan-sát-và-kiểm-thử)
14. [Câu hỏi ôn tập](#14-câu-hỏi-ôn-tập)
15. [Hướng phát triển](#15-hướng-phát-triển)

---

## 1. Bức tranh tổng thể

```text
 ┌──────────── Client (JavaFX) ────────────┐   TLS 1.3  ┌──────────────── Server ────────────────┐
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
| ServerSocket, `accept()`, giới hạn kết nối | [MailServer.java](../mail-server/src/main/java/com/mailsystem/server/net/MailServer.java) |
| Một kết nối = thread đọc + thread ghi | [ClientHandler.java](../mail-server/src/main/java/com/mailsystem/server/net/ClientHandler.java) |
| Framing có giới hạn kích thước | [FrameReader.java](../mail-common/src/main/java/com/mailsystem/common/protocol/FrameReader.java) |
| Định dạng gói tin | [RequestMessage](../mail-common/src/main/java/com/mailsystem/common/protocol/RequestMessage.java), [ResponseMessage](../mail-common/src/main/java/com/mailsystem/common/protocol/ResponseMessage.java), [EventMessage](../mail-common/src/main/java/com/mailsystem/common/protocol/EventMessage.java) |
| Server push nhiều thiết bị | [SessionManager.java](../mail-server/src/main/java/com/mailsystem/server/session/SessionManager.java) |
| Client bất đồng bộ, timeout, heartbeat | [ServerConnection.java](../mail-client/src/main/java/com/mailsystem/client/network/ServerConnection.java) |
| Upload/download theo khối | [UploadManager.java](../mail-server/src/main/java/com/mailsystem/server/transfer/UploadManager.java), [AttachmentTransfer.java](../mail-client/src/main/java/com/mailsystem/client/service/AttachmentTransfer.java), `JdbcMailDAO.getAttachmentChunk` |
| Định tuyến lệnh | [CommandRouter.java](../mail-server/src/main/java/com/mailsystem/server/net/CommandRouter.java) |
| TLS 1.3 phía Server (tự sinh chứng chỉ) | [TlsContextFactory.java](../mail-server/src/main/java/com/mailsystem/server/security/TlsContextFactory.java) |
| TLS phía Client + ghim chứng chỉ (TOFU) | [TlsConnector.java](../mail-client/src/main/java/com/mailsystem/client/network/TlsConnector.java), [TofuTrustManager.java](../mail-client/src/main/java/com/mailsystem/client/network/TofuTrustManager.java) |
| Chống dò mật khẩu, giới hạn tốc độ | [LoginThrottle.java](../mail-server/src/main/java/com/mailsystem/server/security/LoginThrottle.java), [RateLimiter.java](../mail-server/src/main/java/com/mailsystem/server/security/RateLimiter.java) |
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
  │                                              │ accept() trả về SSLSocket mới
  │                                              │ setTcpNoDelay, setKeepAlive, setSoTimeout
  │ ─── ClientHello (khoá ECDHE) ──────────────▶ │
  │ ◀── ServerHello + chứng chỉ + Finished ───── │   bắt tay TLS 1.3 (1 vòng gửi/nhận)
  │ kiểm tra chứng chỉ (TOFU, mục 12)            │
  │ ─── Finished ──────────────────────────────▶ │   từ đây mọi byte đều được mã hoá
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

### 5.3 Gộp thao tác: một request cho nhiều thư

Khi người dùng chọn 20 thư rồi bấm "Chuyển vào Thùng rác", Client có hai lựa chọn:

| Cách | Số request | Số EVENT tới các phiên khác | Số lần các phiên khác tải lại |
| --- | --- | --- | --- |
| Gửi 20 request `{entryId}` | 20 (20 vòng gửi/nhận nếu chờ lần lượt) | 20 | 20 |
| **Gửi 1 request `{entryIds: [20 id]}`** (dự án) | 1 | 1 | 1 |

Trên mạng có độ trễ 50 ms, cách thứ nhất tốn khoảng 1 giây chỉ để chờ mạng nếu
gửi tuần tự. Pipelining (mục 5.2) giảm thời gian chờ nhưng vẫn sinh 20 EVENT,
khiến mỗi thiết bị khác tải lại danh sách 20 lần. Gộp thao tác (*batching*) giảm
cả số vòng gửi/nhận lẫn lưu lượng EVENT. Server giới hạn 500 id mỗi request để
frame có kích thước chặn trên (`MailService.applyToEntries`).

### 5.4 Giao thức có trạng thái (stateful)

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

### 5.5 Định tuyến lệnh (CommandRouter)

Server nhận ~30 loại command. Thay vì một khối `switch` khổng lồ trong
`ClientHandler`, [CommandRouter](../mail-server/src/main/java/com/mailsystem/server/net/CommandRouter.java)
giữ một bảng `Command → handler`, đánh dấu sẵn route nào cần đăng nhập:

```java
open(Command.LOGIN, (id, payload, ctx) -> ...);                    // không cần đăng nhập
secured(Command.SET_STARRED, (id, payload, ctx) -> services.mail().setStarred(id, ctx.accountId(), payload));
```

`ClientHandler` chỉ lo phần mạng (đọc/ghi frame, giới hạn tốc độ, phiên); thêm
command mới chỉ cần thêm một dòng route. Hàm khởi tạo kiểm tra mọi giá trị của
enum `Command` đều có route, nên quên đăng ký sẽ lỗi ngay khi Server khởi động
(và `CommandRouterTest` bắt được).

### 5.6 So sánh với giao thức mail thật

| | Mail System | SMTP / IMAP |
| --- | --- | --- |
| Định dạng | JSON, mỗi dòng 1 gói | Lệnh văn bản (`MAIL FROM:`, `A1 FETCH`) kết thúc `\r\n` |
| Ghép cặp | `requestId` | IMAP dùng *tag* (`A1`, `A2`) đúng như `requestId` |
| Push | EVENT | IMAP `IDLE` (server báo thư mới) |
| Cổng | 5000 (luôn TLS) | SMTP 25/587, IMAP 143/993 (993 = IMAP qua TLS) |

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

Giải pháp trong [ClientHandler](../mail-server/src/main/java/com/mailsystem/server/net/ClientHandler.java):

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
- Client giữ nguyên các thư đang được chọn khi tải lại danh sách do EVENT, nên
  người dùng đang chọn nhiều thư không bị mất lựa chọn khi thư mới đến.
- Mỗi lần nhận EVENT, Client cũng tải lại `GET_FOLDERS` để cập nhật số thư chưa
  đọc trên từng thư mục.

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

## 12. Bảo mật: TLS 1.3 và các lớp phòng thủ

Chi tiết đầy đủ (mô hình mối đe doạ, từng biện pháp, cấu hình) nằm ở
[SECURITY.md](SECURITY.md). Phần này giải thích các khái niệm mạng liên quan.

### 12.1 TLS nằm ở đâu

```text
┌──────────────────────────────┐
│ Giao thức JSON theo dòng     │  ← không đổi một byte nào khi thêm TLS
├──────────────────────────────┤
│ TLS 1.3 (SSLSocket)          │  ← mã hoá + chống sửa + xác thực Server
├──────────────────────────────┤
│ TCP                          │
├──────────────────────────────┤
│ IP                           │
└──────────────────────────────┘
```

TLS là một lớp nằm giữa TCP và tầng ứng dụng. Vì `SSLSocket` cũng là một
`Socket` với `getInputStream()`/`getOutputStream()`, framing, `FrameReader`,
`ClientHandler` và giao thức JSON **không phải sửa gì**. Chỉ đổi chỗ tạo socket:
`SSLServerSocketFactory` ở Server, `SSLSocketFactory` ở Client.

TLS cung cấp ba thứ:

| Thuộc tính | Ý nghĩa | Nếu không có |
| --- | --- | --- |
| Bí mật (confidentiality) | Dữ liệu được mã hoá bằng AES-GCM/ChaCha20 | Ai bắt gói trong LAN cũng đọc được mật khẩu, nội dung thư |
| Toàn vẹn (integrity) | Mỗi bản ghi TLS có mã xác thực (AEAD) | Kẻ ở giữa sửa được nội dung thư mà không ai biết |
| Xác thực Server | Server chứng minh sở hữu khoá riêng của chứng chỉ | Client có thể vô tình gửi mật khẩu cho máy chủ giả |

Dự án chỉ bật **TLS 1.3** (`ProtocolConstants.TLS_PROTOCOLS`): bỏ hẳn các bộ mã
hoá cũ yếu, trao đổi khoá ECDHE nên có *forward secrecy* (lộ khoá riêng của
Server sau này cũng không giải mã được phiên cũ đã bắt), bắt tay chỉ 1 vòng
gửi/nhận.

### 12.2 Chứng chỉ tự ký và TOFU

Website dùng chứng chỉ do CA (Let's Encrypt...) ký vì trình duyệt tin sẵn các
CA đó. Server nội bộ trong LAN không có tên miền công khai nên không xin được
chứng chỉ CA. Giải pháp của dự án, giống SSH:

1. Lần chạy đầu, Server **tự sinh** cặp khoá EC P-256 và chứng chỉ tự ký
   ([TlsContextFactory](../mail-server/src/main/java/com/mailsystem/server/security/TlsContextFactory.java),
   gọi `keytool` của JDK), lưu ở `data/tls/server.p12`, rồi in **dấu vân tay
   SHA-256** của chứng chỉ ra console.
2. Lần đầu Client kết nối tới `host:port` đó, [TofuTrustManager](../mail-client/src/main/java/com/mailsystem/client/network/TofuTrustManager.java)
   hiện hộp thoại với dấu vân tay. Người dùng đối chiếu với dòng Server in ra
   rồi bấm tin. Dấu vân tay được ghi vào `~/.mailsystem/known_servers.properties`.
3. Các lần sau Client tự so khớp. Nếu chứng chỉ **khác** (Server bị thay, hoặc
   có kẻ đứng giữa), Client hiện cảnh báo đỏ và mặc định huỷ kết nối **trước
   khi** gửi mật khẩu.

Đây là mô hình *Trust On First Use*: an toàn với mọi tấn công xảy ra sau lần
kết nối đầu tiên, đổi lại người dùng phải kiểm tra dấu vân tay ở lần đầu.

### 12.3 Các lớp phòng thủ ở tầng ứng dụng

| Mối đe doạ | Biện pháp | Code |
| --- | --- | --- |
| Dò mật khẩu (brute force) | Sai 5 lần → khoá 15 phút theo cả email và IP | `LoginThrottle` |
| Dò email đã đăng ký | Cùng một thông báo lỗi + thời gian phản hồi như nhau | `AuthService`, `PasswordHasher.burnTime` |
| Mật khẩu yếu / lộ DB | Chính sách ≥ 8 ký tự có chữ và số; PBKDF2 210 000 vòng + salt | `PasswordPolicy`, `PasswordHasher` |
| Phiên bị đánh cắp | Đổi mật khẩu / "đăng xuất mọi nơi" thu hồi ngay các phiên khác (EVENT `SESSION_REVOKED` rồi đóng kết nối) | `SessionManager.revokeAll` |
| Client spam request (DoS) | Token bucket 30 req/s (burst 120) mỗi kết nối; spam dai dẳng bị ngắt | `RateLimiter`, `ClientHandler` |
| Đăng ký hàng loạt | 10 tài khoản / IP / giờ | `SlidingWindowLimiter` |
| Tệp độc hại đổi đuôi | Kiểm tra chữ ký đầu tệp (magic bytes) khớp phần mở rộng | `FileSignatures` |
| Lộ mật khẩu DB qua Git | Bí mật nằm ở `config/server.properties` (bị .gitignore) hoặc biến môi trường | `ServerConfig` |
| Giả mạo dòng log | Xoá ký tự xuống dòng trong dữ liệu từ client trước khi ghi log | `SecurityLog` |
| SQL injection, path traversal | `PreparedStatement`; tên tệp lưu trữ do Server sinh (UUID) | DAO, `AttachmentStorage` |

---

## 13. Quan sát và kiểm thử

### 13.1 Nhìn tận mắt giao thức

- **ManualTestClient**: có thread nền in mọi frame nhận được. Mở 2 cửa sổ,
  đăng nhập 2 tài khoản, gửi thư từ cửa sổ 1 sẽ thấy `<< EVENT : NEW_MAIL` ở
  cửa sổ 2.
- **ncat**: giao thức là văn bản theo dòng *bên trong* TLS, nên vẫn gõ tay được
  bằng chế độ TLS của ncat (bản đi kèm Nmap):
  ```text
  ncat --ssl localhost 5000
  {"type":"REQUEST","requestId":"1","command":"PING"}
  ```
  Thử `ncat localhost 5000` (không `--ssl`) sẽ thấy Server đóng kết nối ngay:
  Server không bao giờ nói chuyện không mã hoá.
- **Wireshark** với bộ lọc `tcp.port == 5000` trên adapter *Loopback* (Npcap).
  Quan sát được: bắt tay TCP SYN/SYN-ACK/ACK, rồi bắt tay TLS (`Client Hello`,
  `Server Hello`, `Change Cipher Spec`), sau đó chỉ còn các bản ghi
  `Application Data` đã mã hoá: **không** thấy được mật khẩu hay nội dung thư.
  So sánh độ dài bản ghi với kích thước khối upload (512 KB được chia thành
  nhiều bản ghi TLS tối đa 16 KB) là một bài tập hay.
- **netstat**: `netstat -ano | findstr :5000` liệt kê cổng LISTENING và từng kết
  nối ESTABLISHED. Sau khi đóng sẽ thấy các kết nối ở trạng thái TIME_WAIT.

### 13.2 Test tự động (`scripts/test.ps1`, không cần MySQL)

| Test | Kiểm chứng |
| --- | --- |
| `FrameReaderTest` | Ghép frame bị chia nhỏ, CRLF, frame quá lớn rồi đồng bộ lại, frame dang dở |
| `ServerNetworkTest` | TLS 1.3 thật: dấu vân tay chứng chỉ, client TCP thường bị từ chối, client không tin chứng chỉ bị từ chối, giới hạn tốc độ, PING, pipelining, frame hỏng, frame quá lớn, bắt buộc đăng nhập, từ chối khi quá tải, giải phóng slot |
| `CommandRouterTest` | Mọi command đều có route; mọi command (trừ PING/REGISTER/LOGIN) bị chặn khi chưa đăng nhập |
| `SecurityComponentsTest` | PBKDF2 + salt, chính sách mật khẩu, khoá đăng nhập theo email/IP rồi hết hạn, token bucket, giới hạn đăng ký, magic bytes, tự sinh keystore TLS |
| `TofuTrustManagerTest` | Hỏi lần đầu, ghi nhớ xuống đĩa, cảnh báo khi chứng chỉ đổi, cổng khác = máy chủ khác |
| `SessionManagerTest` | Push tới mọi thiết bị, một thiết bị thoát không ảnh hưởng thiết bị khác |
| `MailServiceTest` | Thao tác hàng loạt `entryIds` (gộp trùng, bỏ qua thư không thuộc mình, chặn lô rỗng/quá lớn), nhóm thư không gửi lại cho người gửi |
| `UploadManagerTest` | Ghép khối, từ chối khối sai/lặp, kiểm tra đuôi và kích thước, dọn tệp tạm |
| `ServerConnectionTest` | Ghép response theo `requestId` khi về ngược thứ tự, EVENT chen giữa, mất kết nối làm hỏng request đang chờ, thông báo quá tải, connect lỗi nhanh |

Kiểm thử end-to-end với MySQL thật: `scripts/e2e.ps1` khởi động một Server riêng
(cổng 5098) và chạy ~50 kịch bản qua TLS bằng `EndToEndCheck`, sau đó tự dọn dữ liệu.

### 13.3 Thử qua mạng LAN

1. Máy chạy Server: mở cổng 5000 trên tường lửa Windows (Inbound rule, TCP 5000).
2. Xem IP LAN của máy đó bằng `ipconfig`, VD `192.168.1.10`.
3. Máy khác chạy Client, mở mục **Máy chủ** ở cuối màn hình đăng nhập, nhập
   `192.168.1.10:5000`. Lần đầu sẽ hiện hộp thoại dấu vân tay: đối chiếu với dòng
   `[TLS] Dau van tay` trên console của Server rồi bấm tin.

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

<details><summary>Thêm TLS có phải sửa framing hay giao thức JSON không?</summary>
Không. TLS là một lớp giữa TCP và ứng dụng; SSLSocket vẫn cung cấp InputStream/OutputStream
như Socket thường. Chỉ đổi chỗ tạo socket (SSLServerSocketFactory / SSLSocketFactory).
</details>

<details><summary>Chứng chỉ tự ký có an toàn không? Ai xác nhận nó?</summary>
Không có CA nào xác nhận, nên Client dùng TOFU: người dùng đối chiếu dấu vân tay SHA-256
ở lần đầu, Client ghi nhớ và cảnh báo nếu sau này chứng chỉ thay đổi. An toàn với mọi tấn
công man-in-the-middle xảy ra sau lần kết nối đầu tiên (giống SSH).
</details>

<details><summary>Vì sao khoá đăng nhập theo cả email lẫn IP?</summary>
Theo email: chặn thử nhiều mật khẩu cho một tài khoản (kể cả đổi IP). Theo IP: chặn thử một
mật khẩu phổ biến cho rất nhiều tài khoản. IP có ngưỡng cao hơn vì nhiều người có thể dùng
chung một địa chỉ NAT.
</details>

---

## 15. Hướng phát triển

| Hướng | Lợi ích | Thay đổi chính |
| --- | --- | --- |
| Mutual TLS (chứng chỉ phía Client) | Chỉ máy được cấp chứng chỉ mới kết nối được | `TlsContextFactory`, `TlsConnector` |
| Chứng chỉ do CA nội bộ ký | Bỏ bước xác nhận dấu vân tay thủ công | Phân phối chứng chỉ CA cho Client |
| Java NIO / Netty hoặc virtual threads | Phục vụ hàng nghìn kết nối | `MailServer`, `ClientHandler` |
| Tự kết nối lại + gửi lại request an toàn | Không phải đăng nhập lại khi rớt mạng ngắn | Phiên có token, request idempotent |
| Sliding window khi upload | Tận dụng băng thông trên đường truyền có RTT cao | `ComposeController`, `UploadManager` |
| Resume upload/download sau khi rớt mạng | Không phải tải lại từ đầu | Giữ `uploadId` qua các kết nối |
| Connection pool JDBC (HikariCP) | Giảm thời gian mở kết nối MySQL | `DatabaseConnection` |
| Framing nhị phân (length-prefix) | Không tốn 33% cho Base64 | `FrameReader` + định dạng gói tin |
