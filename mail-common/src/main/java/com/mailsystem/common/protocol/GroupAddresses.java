package com.mailsystem.common.protocol;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Địa chỉ của nhóm thư, dạng <phần-tên>@group.local. Đuôi riêng nên không bao giờ
 * trùng email của người dùng. Dùng chung cho Client (xem trước khi tạo nhóm) và
 * Server (sinh / kiểm tra địa chỉ) để hai bên cho cùng một kết quả.
 */
public final class GroupAddresses {

    public static final String DOMAIN = "group.local";
    public static final int MAX_LOCAL_PART = 60;

    private GroupAddresses() {
    }

    /** "Lớp LTM 2026" → "lop-ltm-2026": bỏ dấu, chữ thường, ký tự khác chữ/số thành "-". */
    public static String slug(String groupName) {
        String text = groupName == null ? "" : groupName.trim();
        text = text.replace('đ', 'd').replace('Đ', 'D');
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        text = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (text.length() > MAX_LOCAL_PART) {
            text = text.substring(0, MAX_LOCAL_PART).replaceAll("-+$", "");
        }
        return text.isEmpty() ? "nhom" : text;
    }

    public static String address(String localPart) {
        return localPart + "@" + DOMAIN;
    }

    public static boolean isGroupAddress(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).endsWith("@" + DOMAIN);
    }

    /** Phần trước @ do người dùng nhập (có hoặc không kèm đuôi), đã chuẩn hoá chữ thường; null nếu không hợp lệ. */
    public static String normalizeLocalPart(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.endsWith("@" + DOMAIN)) {
            text = text.substring(0, text.length() - DOMAIN.length() - 1);
        }
        return text.length() <= MAX_LOCAL_PART && text.matches("[a-z0-9]+([.-][a-z0-9]+)*") ? text : null;
    }
}
