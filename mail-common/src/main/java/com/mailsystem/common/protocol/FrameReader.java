package com.mailsystem.common.protocol;

import java.io.IOException;
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

    private final Reader in;
    private final int maxChars;
    private final char[] buffer = new char[8192];
    private int position;
    private int limit;

    public FrameReader(Reader in, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
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
        StringBuilder frame = new StringBuilder();
        boolean overflow = false;
        while (true) {
            if (position == limit) {
                limit = in.read(buffer, 0, buffer.length);
                position = 0;
                if (limit == -1) {
                    limit = 0;
                    return null;
                }
            }
            int start = position;
            while (position < limit && buffer[position] != '\n') {
                position++;
            }
            if (!overflow) {
                frame.append(buffer, start, position - start);
                if (frame.length() > maxChars + 1) {
                    overflow = true;
                    frame.setLength(0);
                    frame.trimToSize();
                }
            }
            if (position < limit) {
                position++; // bỏ qua '\n'
                if (overflow) {
                    throw new FrameTooLargeException(maxChars);
                }
                int length = frame.length();
                if (length > 0 && frame.charAt(length - 1) == '\r') {
                    frame.setLength(length - 1);
                }
                if (frame.length() > maxChars) {
                    throw new FrameTooLargeException(maxChars);
                }
                return frame.toString();
            }
        }
    }
}
