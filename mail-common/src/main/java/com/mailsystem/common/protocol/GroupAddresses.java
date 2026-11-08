// Gói "protocol": các lớp mô tả định dạng tin nhắn giữa Client và Server.
package com.mailsystem.common.protocol;

// Normalizer: tách chữ có dấu thành chữ gốc + dấu (để bỏ dấu tiếng Việt).
import java.text.Normalizer;
// Locale.ROOT: đổi chữ hoa/thường không phụ thuộc ngôn ngữ của máy.
import java.util.Locale;

/**
 * Địa chỉ của nhóm thư, dạng <phần-tên>@group.local. Đuôi riêng nên không bao giờ
 * trùng email của người dùng. Dùng chung cho Client (xem trước khi tạo nhóm) và
 * Server (sinh / kiểm tra địa chỉ) để hai bên cho cùng một kết quả.
 */
public final class GroupAddresses {

    // Tên miền (phần sau @) dành riêng cho nhóm thư.
    public static final String DOMAIN = "group.local";
    // Độ dài tối đa của phần trước @.
    public static final int MAX_LOCAL_PART = 60;

    // Constructor private: lớp tiện ích chỉ có hàm tĩnh.
    private GroupAddresses() {
    }

    /** "Lớp LTM 2026" → "lop-ltm-2026": bỏ dấu, chữ thường, ký tự khác chữ/số thành "-". */
    public static String slug(String groupName) {
        // Tên null thì coi như rỗng; bỏ khoảng trắng hai đầu.
        String text = groupName == null ? "" : groupName.trim();
        // Chữ đ/Đ không tách dấu được bằng Normalizer nên đổi tay sang d/D.
        text = text.replace('đ', 'd').replace('Đ', 'D');
        // NFD tách "ớ" thành "o" + các dấu; xoá mọi ký tự dấu (nhóm Unicode \p{M}).
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        // Chữ thường; mỗi chuỗi ký tự không phải a-z/0-9 thành một dấu "-"; bỏ "-" ở đầu và cuối.
        text = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        // Quá dài thì cắt còn MAX_LOCAL_PART ký tự, rồi bỏ "-" thừa ở cuối do bị cắt.
        if (text.length() > MAX_LOCAL_PART) {
            text = text.substring(0, MAX_LOCAL_PART).replaceAll("-+$", "");
        }
        // Tên toàn ký tự đặc biệt (kết quả rỗng) thì dùng "nhom".
        return text.isEmpty() ? "nhom" : text;
    }

    /**
     * Ghép phần tên với đuôi nhóm.
     *
     * @param localPart phần trước @, vd "lop-ltm"
     * @return địa chỉ đầy đủ, vd "lop-ltm@group.local"
     */
    public static String address(String localPart) {
        return localPart + "@" + DOMAIN;
    }

    /**
     * Kiểm tra một chuỗi có phải địa chỉ nhóm không (đuôi @group.local, không phân biệt hoa thường).
     *
     * @param value chuỗi người nhận
     * @return true nếu là địa chỉ nhóm
     */
    public static boolean isGroupAddress(String value) {
        // Khác null, bỏ khoảng trắng, đổi chữ thường rồi xem có kết thúc bằng "@group.local".
        return value != null && value.trim().toLowerCase(Locale.ROOT).endsWith("@" + DOMAIN);
    }

    /** Phần trước @ do người dùng nhập (có hoặc không kèm đuôi), đã chuẩn hoá chữ thường; null nếu không hợp lệ. */
    public static String normalizeLocalPart(String value) {
        // Không có giá trị thì không hợp lệ.
        if (value == null) {
            return null;
        }
        // Bỏ khoảng trắng hai đầu, đổi chữ thường.
        String text = value.trim().toLowerCase(Locale.ROOT);
        // Người dùng gõ cả đuôi "@group.local" thì cắt bỏ đuôi (độ dài đuôi + 1 ký tự '@').
        if (text.endsWith("@" + DOMAIN)) {
            text = text.substring(0, text.length() - DOMAIN.length() - 1);
        }
        // Hợp lệ khi không quá dài và chỉ gồm chữ/số, có thể nối bằng '.' hoặc '-' (không ở đầu/cuối, không liền nhau).
        return text.length() <= MAX_LOCAL_PART && text.matches("[a-z0-9]+([.-][a-z0-9]+)*") ? text : null;
    }
}
