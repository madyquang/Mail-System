// Gói "ui": các thành phần giao diện tự viết (JavaFX).
package com.mailsystem.client.ui;

// MailApi (gọi SUGGEST_CONTACTS), DTO Contact, ô nhập JavaFX.
import com.mailsystem.client.api.MailApi;
import com.mailsystem.common.model.Contact;
import javafx.scene.control.TextField;

// Chữ thường, tập hợp, gom luồng.
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Gợi ý người nhận (người + nhóm) dựng sẵn để gắn vào các ô nhập địa chỉ. */
public final class ContactSuggestions {

    // Constructor private: lớp chỉ có hàm tĩnh.
    private ContactSuggestions() {
    }

    /** Ô Tới/Cc/Bcc: gợi ý cho địa chỉ đang gõ sau dấu phẩy, bỏ qua địa chỉ đã có trong ô. */
    public static AutoComplete forRecipients(TextField field) {
        // Mảng 1 phần tử để lambda bên dưới tham chiếu được AutoComplete (tạo xong mới có).
        AutoComplete[] holder = new AutoComplete[1];
        // Gắn gợi ý kiểu nhiều địa chỉ (cách nhau dấu phẩy); mỗi lần gõ gọi Server lấy tối đa 8 gợi ý (có nhóm).
        holder[0] = AutoComplete.attachTokens(field, query -> MailApi.get().suggestContacts(query, true, 8)
                .thenApply(contacts -> {
                    // Các địa chỉ đã điền đầy đủ trong ô (chữ thường).
                    Set<String> used = holder[0].completedTokens().stream()
                            .map(token -> token.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                    // Bỏ gợi ý trùng địa chỉ đã có; mỗi gợi ý khi chọn sẽ thay phần đang gõ bằng địa chỉ đó.
                    return contacts.stream()
                            .filter(contact -> !used.contains(contact.address().toLowerCase(Locale.ROOT)))
                            .map(contact -> toSuggestion(contact, () -> holder[0].replaceCurrent(contact.address())))
                            .toList();
                }));
        return holder[0];
    }

    /** Ô một email (lọc người gửi, thêm thành viên nhóm): chỉ gợi ý người. */
    public static AutoComplete forSinglePerson(TextField field, Runnable afterPick) {
        // Mảng 1 phần tử như trên.
        AutoComplete[] holder = new AutoComplete[1];
        // Gắn gợi ý một giá trị (gõ từ 1 ký tự, chỉ người, tối đa 6).
        holder[0] = AutoComplete.attach(field, 1, true, query -> MailApi.get().suggestContacts(query, false, 6)
                .thenApply(contacts -> contacts.stream()
                        .map(contact -> toSuggestion(contact, () -> {
                            // Chọn: điền email rồi chạy việc tiếp theo (nếu có).
                            holder[0].replaceCurrent(contact.address());
                            if (afterPick != null) {
                                afterPick.run();
                            }
                        }))
                        .toList()));
        return holder[0];
    }

    /** Đổi Contact thành một dòng gợi ý (nhóm hoặc người). */
    public static AutoComplete.Suggestion toSuggestion(Contact contact, Runnable onPick) {
        // Nhóm: tiêu đề mục "Nhóm thư", tên nhóm, dòng phụ = địa chỉ + số thành viên.
        if (contact.isGroup()) {
            String address = contact.getGroupAddress() == null ? "" : contact.getGroupAddress() + "  ·  ";
            return new AutoComplete.Suggestion("Nhóm thư", Icons.GROUPS, contact.getGroupName(),
                    address + contact.getMemberCount() + " thành viên", onPick);
        }
        // Người: tên hiển thị (trống thì email), dòng phụ = email + số thư đã trao đổi.
        String name = contact.getDisplayName() == null || contact.getDisplayName().isBlank()
                ? contact.getEmail() : contact.getDisplayName();
        String subtitle = contact.getEmail()
                + (contact.getInteractions() > 0 ? "  ·  đã trao đổi " + contact.getInteractions() + " thư" : "");
        return new AutoComplete.Suggestion("Mọi người", Icons.PERSON, name, subtitle, onPick);
    }
}
