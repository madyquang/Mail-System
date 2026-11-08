// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// IOException: lỗi vào/ra khi đọc luồng.
import java.io.IOException;
// Reader: luồng đọc ký tự (ở đây là luồng vào của socket, đã giải mã UTF-8).
import java.io.Reader;

/**
 * Tách luồng ký tự TCP thành từng frame, mỗi frame kết thúc bằng '\n'.
 *
 * TCP là luồng byte (stream), KHÔNG giữ ranh giới giữa các lần gửi: một lần
 * write() bên gửi có thể tới bên nhận thành nhiều lần read(), và nhiều lần
 * write() có thể bị gộp vào một lần read(). Vì vậy giao thức tự định nghĩa
 * ranh giới gói tin (framing) bằng ký tự xuống dòng. JSON do Gson sinh ra không
 * chứa '\n' thô (xuống dòng trong chuỗi được escape thành "\\n"), nên '\n' luôn
 * là dấu kết thúc frame.
 *
 * Khác BufferedReader.readLine(), lớp này giới hạn độ dài frame: dòng quá dài
 * bị đọc bỏ (không giữ trong bộ nhớ) và báo FrameTooLargeException.
 * Không thread-safe: mỗi kết nối chỉ có một thread đọc.
 */
public final class FrameReader {

    // Luồng ký tự nguồn.
    private final Reader in;
    // Số ký tự tối đa của một frame.
    private final int maxChars;
    // Bộ đệm 8192 ký tự: đọc từng mẻ lớn từ luồng thay vì từng ký tự một (nhanh hơn).
    private final char[] buffer = new char[8192];
    // Vị trí ký tự kế tiếp cần xử lý trong bộ đệm.
    private int position;
    // Số ký tự hợp lệ hiện có trong bộ đệm (position == limit nghĩa là đã dùng hết).
    private int limit;

    /**
     * Tạo bộ đọc frame.
     *
     * @param in       luồng ký tự nguồn
     * @param maxChars số ký tự tối đa của một frame (phải dương)
     */
    public FrameReader(Reader in, int maxChars) {
        // Giới hạn không hợp lệ thì báo lỗi ngay.
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        // Lưu luồng nguồn và giới hạn.
        this.in = in;
        this.maxChars = maxChars;
    }

    /**
     * Đọc frame kế tiếp (không gồm "\n" hoặc "\r\n").
     *
     * @return frame, hoặc null khi bên kia đã đóng kết nối. Một đoạn dữ liệu
     *         chưa có '\n' lúc kết nối đóng là frame dang dở và bị bỏ qua.
     * @throws FrameTooLargeException frame dài hơn maxChars (luồng vẫn đồng bộ)
     */
    public String readFrame() throws IOException {
        // Nơi gom các ký tự của frame đang đọc.
        StringBuilder frame = new StringBuilder();
        // true khi frame đã vượt giới hạn: từ đó chỉ đọc bỏ tới '\n', không lưu nữa.
        boolean overflow = false;
        // Lặp cho tới khi gặp '\n' (trả frame) hoặc luồng kết thúc (trả null).
        while (true) {
            // Bộ đệm đã dùng hết: đọc mẻ mới từ luồng (chặn chờ tới khi có dữ liệu).
            if (position == limit) {
                // read() trả số ký tự đọc được, hoặc -1 khi bên kia đã đóng kết nối.
                limit = in.read(buffer, 0, buffer.length);
                // Bắt đầu xử lý từ đầu bộ đệm.
                position = 0;
                // Hết luồng: đặt lại limit = 0 và báo hết bằng null.
                if (limit == -1) {
                    limit = 0;
                    return null;
                }
            }
            // Ghi nhớ vị trí bắt đầu của đoạn sắp quét.
            int start = position;
            // Quét tới '\n' hoặc tới hết dữ liệu trong bộ đệm.
            while (position < limit && buffer[position] != '\n') {
                position++;
            }
            // Chưa vượt giới hạn thì giữ lại đoạn vừa quét.
            if (!overflow) {
                // Nối đoạn [start, position) vào frame.
                frame.append(buffer, start, position - start);
                // Cho phép dư 1 ký tự (là '\r' của "\r\n"); dài hơn nữa là vượt giới hạn.
                if (frame.length() > maxChars + 1) {
                    // Đánh dấu vượt giới hạn và giải phóng bộ nhớ đã gom.
                    overflow = true;
                    frame.setLength(0);
                    frame.trimToSize();
                }
            }
            // Còn ký tự trong bộ đệm nghĩa là vòng quét dừng vì gặp '\n': đã hết frame.
            if (position < limit) {
                position++; // bỏ qua '\n'
                // Frame này đã bị đọc bỏ vì quá dài: báo lỗi (luồng đã ở đầu frame kế tiếp).
                if (overflow) {
                    throw new FrameTooLargeException(maxChars);
                }
                // Bỏ '\r' cuối nếu bên gửi dùng kiểu xuống dòng "\r\n" (Windows).
                int length = frame.length();
                if (length > 0 && frame.charAt(length - 1) == '\r') {
                    frame.setLength(length - 1);
                }
                // Kiểm tra lại giới hạn sau khi bỏ '\r'.
                if (frame.length() > maxChars) {
                    throw new FrameTooLargeException(maxChars);
                }
                // Trả nội dung frame.
                return frame.toString();
            }
            // Chưa gặp '\n' (bộ đệm hết giữa chừng): quay lại đọc mẻ tiếp theo.
        }
    }
}
