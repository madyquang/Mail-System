// Gói "security": các cơ chế bảo mật phía Server.
package com.mailsystem.server.security;

// IOException: lỗi vào/ra khi đọc tệp.
import java.io.IOException;
// InputStream: luồng đọc byte từ tệp.
import java.io.InputStream;
// ByteBuffer: vùng byte để bộ giải mã UTF-8 đọc.
import java.nio.ByteBuffer;
// Lỗi khi dãy byte không phải UTF-8 hợp lệ.
import java.nio.charset.CharacterCodingException;
// CodingErrorAction.REPORT: gặp byte sai thì báo lỗi (không thay bằng ký tự '?').
import java.nio.charset.CodingErrorAction;
// StandardCharsets.UTF_8: bảng mã UTF-8.
import java.nio.charset.StandardCharsets;
// Files / Path: mở tệp theo đường dẫn.
import java.nio.file.Files;
import java.nio.file.Path;
// Arrays: so sánh / sao chép mảng.
import java.util.Arrays;

/**
 * Kiểm tra "chữ ký" đầu tệp (magic bytes) có khớp phần mở rộng hay không.
 * Chặn trường hợp đổi tên virus.exe thành baocao.pdf: phần mở rộng hợp lệ
 * nhưng nội dung không phải PDF.
 */
public final class FileSignatures {

    // Tệp PDF bắt đầu bằng "%PDF-".
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    // Tệp PNG bắt đầu bằng 8 byte cố định: 0x89 "PNG" CR LF 0x1A LF.
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    // Tệp JPEG bắt đầu bằng FF D8 FF.
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    // Tệp ZIP (và docx/pptx, vốn là ZIP) bắt đầu bằng "PK" 03 04.
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    // Tệp ZIP rỗng (không có tệp con) bắt đầu bằng "PK" 05 06.
    private static final byte[] ZIP_EMPTY = {'P', 'K', 0x05, 0x06};
    // Với tệp .txt, đọc tối đa 64 KB đầu để kiểm tra có phải văn bản không.
    private static final int TEXT_SAMPLE_BYTES = 64 * 1024;

    // Constructor private: lớp chỉ có hàm tĩnh.
    private FileSignatures() {
    }

    /** @return true nếu nội dung phù hợp với phần mở rộng (docx/pptx là tệp ZIP). */
    public static boolean matches(String extension, byte[] head) {
        // Chọn cách kiểm tra theo đuôi tệp.
        switch (extension) {
            case "pdf":
                return startsWith(head, PDF);
            case "png":
                return startsWith(head, PNG);
            case "jpg":
                return startsWith(head, JPG);
            case "zip":
                // ZIP thường hoặc ZIP rỗng đều được.
                return startsWith(head, ZIP) || startsWith(head, ZIP_EMPTY);
            case "docx":
            case "pptx":
                // docx/pptx là ZIP chứa tệp XML.
                return startsWith(head, ZIP);
            case "txt":
                // Văn bản không có chữ ký cố định: kiểm tra nội dung.
                return isText(head);
            default:
                // Đuôi không được hỗ trợ.
                return false;
        }
    }

    /**
     * Kiểm tra tệp trên đĩa (đọc một phần đầu tệp rồi gọi hàm ở trên).
     *
     * @param extension đuôi tệp (chữ thường)
     * @param file      đường dẫn tệp
     * @return true nếu nội dung khớp đuôi
     */
    public static boolean matches(String extension, Path file) throws IOException {
        // Tệp văn bản cần mẫu lớn; các loại khác chỉ cần 16 byte đầu.
        int sample = "txt".equals(extension) ? TEXT_SAMPLE_BYTES : 16;
        byte[] head;
        // Mở tệp và đọc tối đa "sample" byte (try-with-resources tự đóng tệp).
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(sample);
        }
        return matches(extension, head);
    }

    // Dữ liệu có bắt đầu bằng dãy byte prefix không.
    private static boolean startsWith(byte[] data, byte[] prefix) {
        // Đủ dài, và phần đầu (cắt đúng độ dài prefix) bằng prefix.
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }

    /** Văn bản: không chứa byte NUL và là UTF-8 hợp lệ (bỏ qua ký tự bị cắt ở cuối mẫu). */
    private static boolean isText(byte[] data) {
        // Có byte 0 (NUL) thì là tệp nhị phân.
        for (byte b : data) {
            if (b == 0) {
                return false;
            }
        }
        int length = data.length;
        // Mẫu có thể cắt ngang một ký tự UTF-8 nhiều byte ở cuối: lùi tối đa 3 byte.
        for (int trim = 0; trim <= 3 && length - trim >= 0; trim++) {
            try {
                // Giải mã UTF-8 nghiêm ngặt phần [0, length - trim); byte sai sẽ ném lỗi.
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, 0, length - trim));
                // Giải mã được: là văn bản.
                return true;
            } catch (CharacterCodingException error) {
                if (data.length < TEXT_SAMPLE_BYTES) {
                    return false; // đã đọc hết tệp: không có ký tự bị cắt
                }
                // Mẫu bị cắt ở 64 KB: thử lùi thêm 1 byte ở vòng lặp sau.
            }
        }
        // Lùi 3 byte vẫn sai: không phải UTF-8.
        return false;
    }
}
