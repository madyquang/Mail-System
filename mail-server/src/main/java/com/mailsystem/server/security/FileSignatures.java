package com.mailsystem.server.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Kiểm tra "chữ ký" đầu tệp (magic bytes) có khớp phần mở rộng hay không.
 * Chặn trường hợp đổi tên virus.exe thành baocao.pdf: phần mở rộng hợp lệ
 * nhưng nội dung không phải PDF.
 */
public final class FileSignatures {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] ZIP_EMPTY = {'P', 'K', 0x05, 0x06};
    private static final int TEXT_SAMPLE_BYTES = 64 * 1024;

    private FileSignatures() {
    }

    /** @return true nếu nội dung phù hợp với phần mở rộng (docx/pptx là tệp ZIP). */
    public static boolean matches(String extension, byte[] head) {
        switch (extension) {
            case "pdf":
                return startsWith(head, PDF);
            case "png":
                return startsWith(head, PNG);
            case "jpg":
                return startsWith(head, JPG);
            case "zip":
                return startsWith(head, ZIP) || startsWith(head, ZIP_EMPTY);
            case "docx":
            case "pptx":
                return startsWith(head, ZIP);
            case "txt":
                return isText(head);
            default:
                return false;
        }
    }

    public static boolean matches(String extension, Path file) throws IOException {
        int sample = "txt".equals(extension) ? TEXT_SAMPLE_BYTES : 16;
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(sample);
        }
        return matches(extension, head);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }

    /** Văn bản: không chứa byte NUL và là UTF-8 hợp lệ (bỏ qua ký tự bị cắt ở cuối mẫu). */
    private static boolean isText(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return false;
            }
        }
        int length = data.length;
        // Mẫu có thể cắt ngang một ký tự UTF-8 nhiều byte ở cuối: lùi tối đa 3 byte.
        for (int trim = 0; trim <= 3 && length - trim >= 0; trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, 0, length - trim));
                return true;
            } catch (CharacterCodingException error) {
                if (data.length < TEXT_SAMPLE_BYTES) {
                    return false; // đã đọc hết tệp: không có ký tự bị cắt
                }
            }
        }
        return false;
    }
}
