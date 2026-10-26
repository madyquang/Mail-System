# FEATURES — Chức năng và ràng buộc (v3.0)

Ứng dụng mail nội bộ: thư chỉ gửi giữa các tài khoản đăng ký trên cùng một
Server, không gửi ra dịch vụ email bên ngoài. Mọi kết nối được mã hoá TLS 1.3.

## 1. Đăng ký, đăng nhập, kết nối

- Màn hình đăng nhập dạng thẻ ở giữa, có nút ẩn/hiện mật khẩu, nhớ email lần
  trước (không nhớ mật khẩu), nhãn "Mã hoá TLS 1.3".
- Mục **Máy chủ** thu gọn ở cuối thẻ (`host:port`, mặc định `localhost:5000`,
  hoặc biến môi trường `MAIL_SERVER_HOST`/`MAIL_SERVER_PORT`). Ứng dụng chỉ kết
  nối khi bấm Đăng nhập/Đăng ký nên vẫn mở được khi Server chưa chạy.
- Lần đầu kết nối tới một máy chủ: hộp thoại hiện **dấu vân tay chứng chỉ** để
  đối chiếu; chứng chỉ đổi bất thường thì hiện cảnh báo đỏ.
- Đăng ký: tên hiển thị (≤ 100 ký tự), email đúng định dạng và chưa dùng, mật
  khẩu ≥ 8 ký tự có chữ và số (thanh độ mạnh mật khẩu), nhập lại khớp.
- Sai mật khẩu 5 lần: tạm khoá đăng nhập 15 phút (khởi động lại Server vẫn giữ khoá).
- Tài khoản bật xác minh 2 bước: sau mật khẩu đúng, thẻ đăng nhập hiện thêm ô
  **Mã xác minh 2 bước** (6 số từ Google/Microsoft Authenticator).
- Mất kết nối khi đang dùng: dải thông báo "Thử kết nối lại sau N giây (lần K)"
  hiện dưới thanh trên cùng, ứng dụng **tự kết nối lại** (1, 2, 4, 8, 16, 30 giây...)
  và đăng nhập lại bằng token, không cần nhập mật khẩu. Thao tác xem thư trong
  lúc đó được gửi lại tự động. Chỉ khi token hết hạn / Server từ chối / quá 5
  phút mới quay về màn hình đăng nhập.

## 2. Màn hình chính (bố cục 3 cột)

- **Thanh trên**: thu gọn thanh bên, ô tìm kiếm (từ khoá + người gửi), chip
  trạng thái kết nối (TLS 1.3 · máy chủ; di chuột xem bộ mã hoá và dấu vân tay),
  làm mới, nhóm thư, menu tài khoản (ảnh đại diện).
- **Thanh bên**: nút Soạn thư; Hộp thư đến (số chưa đọc), Có gắn sao, Đã gửi,
  Thư nháp (số bản nháp), Thùng rác.
- **Danh sách thư**: ảnh đại diện, người gửi, giờ (hôm nay "09:30", hôm qua,
  "12 thg 10"...), tiêu đề, đoạn trích, kẹp giấy nếu có tệp, ngôi sao bấm được.
  Thư chưa đọc in đậm + chấm xanh. View "Có gắn sao" và kết quả tìm kiếm hiện
  thêm nhãn thư mục.
- **Khung đọc thư**: tiêu đề, người gửi, người nhận ("tôi" thay cho email của
  mình), thời gian, nội dung (chọn và sao chép được), tệp đính kèm dạng thẻ có
  thanh tiến độ khi tải; thanh công cụ Trả lời, Trả lời tất cả, Chuyển tiếp, Gắn
  sao, Đánh dấu chưa đọc, Xoá (hoặc Khôi phục / Xoá vĩnh viễn trong Thùng rác).
- **Trạng thái**: đang tải (vòng quay), danh sách rỗng (thông điệp riêng từng
  thư mục), lỗi tải (dải đỏ + "Thử lại"), toast thành công/thất bại ở cạnh dưới.
- Co giãn cửa sổ (tối thiểu 960×640): danh sách và khung đọc chia bằng thanh
  kéo; nội dung dài tự xuống dòng hoặc cắt "…".

## 3. Thao tác với thư

- **Chọn nhiều thư**: Ctrl/Shift + click, ô "Chọn tất cả", Ctrl+A. Thanh thao
  tác hiện số thư đang chọn với: đánh dấu đã đọc / chưa đọc, gắn / bỏ sao, chuyển
  vào Thùng rác; trong Thùng rác: khôi phục, xoá vĩnh viễn. Cả lô gửi trong một request.
- Mở thư tự đánh dấu đã đọc (trừ thư trong Thùng rác).
- Khôi phục đưa thư về đúng thư mục trước khi xoá. Xoá vĩnh viễn và Dọn sạch
  Thùng rác cần xác nhận.
- Tìm kiếm theo từ khoá trong tiêu đề/nội dung và/hoặc người gửi (email hoặc tên),
  trong thư mục đang mở; ở "Có gắn sao" tìm trên mọi thư mục.
- **Phím tắt**: Ctrl+N soạn thư, Ctrl+F tìm kiếm, Esc xoá tìm kiếm, F5 làm mới,
  Delete xoá, S gắn sao, U chưa đọc, R trả lời, A trả lời tất cả, F chuyển tiếp,
  Ctrl+Enter gửi, Ctrl+S lưu nháp.

## 4. Gợi ý khi gõ

- **Ô Tới / Cc / Bcc**: gõ 1 ký tự trở lên là hiện gợi ý người (theo email hoặc
  tên có dấu) trong toàn bộ tài khoản, và nhóm thư mình là thành viên (theo tên
  hoặc địa chỉ nhóm). Người đã trao đổi thư nhiều được xếp trước, kèm số thư;
  chọn nhóm thì điền địa chỉ `...@group.local`. Gợi ý chỉ áp dụng cho địa chỉ đang gõ sau
  dấu phẩy cuối và bỏ qua địa chỉ đã có trong ô; chọn xong tự thêm ", " để gõ tiếp.
- **Ô tìm thư**: khi ô trống hiện tối đa 8 từ khoá tìm gần đây (lưu trên máy);
  gõ từ 2 ký tự hiện: "Tìm ... trong mọi thư mục" kèm số thư khớp, tối đa 5 thư
  khớp (chọn là mở thư đó ngay) và người gửi khớp (chọn là lọc thư từ người đó).
- **Ô Người gửi** (bộ lọc tìm kiếm) và **ô thêm thành viên nhóm**: gợi ý người
  (mọi tài khoản, người đã trao đổi thư xếp trước).
- Bàn phím: ↓ / ↑ chọn gợi ý, Enter hoặc Tab dùng gợi ý, Esc đóng; bấm chuột cũng chọn được.

## 5. Soạn thư

- Mỗi thư soạn trong một cửa sổ riêng (mở được nhiều cửa sổ cùng lúc).
- Tới / Cc / Bcc (Cc, Bcc ẩn mặc định), phân cách bằng dấu phẩy; người nhận là
  email hoặc **tên nhóm**. Một người nhận sai → cả thư bị từ chối, báo rõ người nhận đó.
- **Trả lời / Trả lời tất cả**: tự điền người nhận (bỏ chính mình), tiêu đề "Re:",
  trích dẫn nội dung cũ. **Chuyển tiếp**: tiêu đề "Fwd:", phần đầu thư gốc, giữ
  nguyên tệp đính kèm của thư gốc (bỏ bớt được).
- Tệp đính kèm: tối đa 5 tệp, tổng 25 MB, chỉ PDF, DOCX, PPTX, TXT, JPG, PNG, ZIP;
  tải lên theo khối 512 KB có thanh tiến độ. Tệp đổi đuôi giả (VD: .exe thành
  .pdf) bị máy chủ từ chối.
- **Thư nháp**: tự lưu mỗi 20 giây khi có thay đổi, Lưu nháp bằng Ctrl+S; đóng cửa
  sổ khi chưa gửi thì hỏi Lưu nháp / Bỏ thư / Tiếp tục soạn. Gửi từ nháp thì bản
  nháp tự xoá. Nháp không lưu tệp đính kèm. Tối đa 200 bản nháp / tài khoản.
- Tiêu đề ≤ 255 ký tự (để trống thì hỏi xác nhận); nội dung ≤ 100 000 ký tự.

## 6. Thông báo

- Thư mới đến khi cửa sổ không được chọn: thông báo desktop ở góc màn hình
  (Windows), bấm vào để mở ứng dụng. Khi đang dùng ứng dụng: toast trong cửa sổ.
- Tiêu đề cửa sổ hiện số thư chưa đọc, VD "Hộp thư đến (3) – Mail System".
- Mọi thay đổi (thư mới, đã đọc, gắn sao, xoá, khôi phục, nháp, tên hiển thị)
  đồng bộ ngay tới mọi thiết bị đang đăng nhập cùng tài khoản.

## 7. Tài khoản & bảo mật

- **Hồ sơ**: đổi tên hiển thị (đồng bộ tới các thiết bị khác).
- **Bảo mật**: đổi mật khẩu (cần mật khẩu hiện tại; các thiết bị khác bị đăng
  xuất ngay), đăng xuất khỏi mọi thiết bị.
- **Kết nối**: máy chủ, phiên bản TLS, bộ mã hoá, dấu vân tay chứng chỉ.

## 8. Nhóm thư

- Tạo nhóm (tên ≤ 100 ký tự, không chứa `@` hoặc dấu phẩy, không trùng kể cả
  khác hoa/thường); người tạo là chủ nhóm và thành viên đầu tiên.
- **Địa chỉ nhóm** `...@group.local`: gõ tên nhóm thì ô địa chỉ tự điền (bỏ dấu,
  chữ thường, khoảng trắng thành `-`), người tạo sửa được trước khi bấm Tạo.
  Địa chỉ hiện ở chi tiết nhóm (nút sao chép), trong danh sách nhóm, gợi ý khi gõ
  và thư thông báo thêm vào nhóm. Gửi tới nhóm bằng địa chỉ hoặc tên đều được.
- Chủ nhóm thêm/xoá thành viên (email phải có tài khoản), xoá nhóm; thành viên
  thường rời nhóm. Chủ nhóm không tự xoá mình / rời nhóm.
- Chỉ thành viên được gửi tới nhóm; người gửi không nhận lại thư của chính mình.
- **Người được thêm vào nhóm luôn được báo**: một thư thông báo (gửi danh nghĩa
  chủ nhóm) vào Hộp thư đến, kèm thông báo desktop nếu đang online; offline thì
  lần đăng nhập sau vẫn thấy thư. Thư cho biết ai thêm, nhóm có bao nhiêu người,
  cách gửi thư cho nhóm và cách rời nhóm. Tương tự khi bị xoá khỏi nhóm hoặc
  nhóm bị xoá. Thành viên tự rời nhóm thì chủ nhóm nhận thông báo trong ứng dụng.
  Thư thông báo không nằm trong mục Đã gửi của chủ nhóm. Cửa sổ Nhóm thư đang mở
  tự cập nhật.

## 9. Tính năng v3

| Tính năng | Cách dùng |
| --- | --- |
| **Thư mục tự tạo** | Mục "Thư mục" ở thanh bên: nút + để tạo, chuột phải để đổi tên / xoá (thư bên trong vào Thùng rác). **Kéo thư** từ danh sách thả vào thư mục, Hộp thư đến hoặc Thùng rác; hoặc nút "Chuyển tới" trên thanh thao tác khi chọn nhiều thư |
| **Hội thoại** | Trả lời tự nối vào hội thoại. Danh sách gộp các thư cùng hội thoại thành một dòng có số thư; khung đọc xếp thư cũ → mới, thư cũ thu gọn (bấm để mở). Bật/tắt ở menu ảnh đại diện → "Gộp thư theo hội thoại" |
| **Hẹn giờ gửi** | Nút lịch cạnh nút Gửi: sau 1 giờ, sáng mai 8:00, chiều mai 13:00, sáng thứ Hai, hoặc tự chọn ngày giờ. Mục **Đã lên lịch** liệt kê thư chờ, nút "Huỷ & sửa" |
| **Giao diện tối** | Menu ảnh đại diện → "Giao diện tối"; đổi ngay cho mọi cửa sổ, được nhớ cho lần sau |
| **Xem trước tệp** | Ảnh PNG/JPG, PDF (từng trang) và TXT mở ngay trong ứng dụng (nút con mắt); vẫn có nút lưu về máy / mở bằng ứng dụng khác |
| **Kéo thả tệp vào thư** | Kéo tệp từ Explorer thả vào cửa sổ soạn thư |
| **Báo đã đọc** | Người gửi thấy "Đã xem: Bình (20:06)" trong thư đã gửi và nhận thông báo khi người nhận mở thư. Tắt ở Tài khoản → Bảo mật → Quyền riêng tư |
| **Xác minh 2 bước** | Tài khoản → Bảo mật → Bật: quét QR, nhập mã để xác nhận |
| **Chứng chỉ thiết bị** | Tài khoản → Kết nối hiện dấu vân tay thiết bị để gửi quản trị viên duyệt |
| **Tải tiếp khi rớt mạng** | Tệp đang tải lên/xuống tiếp tục từ chỗ dừng sau khi kết nối lại |

## 10. Giới hạn hiện tại

- Chưa gửi/nhận qua Internet (SMTP/IMAP), chưa có mã khôi phục cho 2FA.
- Hẹn giờ dùng giờ của máy chủ (Client và Server cùng múi giờ trong mạng nội bộ).
- Huỷ thư hẹn giờ từ mục Đã lên lịch thì phải đính kèm lại tệp (tệp đã nằm ở
  Server, không còn trên máy).
- Bấm Gửi là thư đi ngay, không có hoàn tác (đã bỏ theo yêu cầu).
