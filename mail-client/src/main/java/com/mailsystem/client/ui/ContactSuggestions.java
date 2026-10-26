package com.mailsystem.client.ui;

import com.mailsystem.client.api.MailApi;
import com.mailsystem.common.model.Contact;
import javafx.scene.control.TextField;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Gợi ý người nhận (người + nhóm) dựng sẵn để gắn vào các ô nhập địa chỉ. */
public final class ContactSuggestions {

    private ContactSuggestions() {
    }

    /** Ô Tới/Cc/Bcc: gợi ý cho địa chỉ đang gõ sau dấu phẩy, bỏ qua địa chỉ đã có trong ô. */
    public static AutoComplete forRecipients(TextField field) {
        AutoComplete[] holder = new AutoComplete[1];
        holder[0] = AutoComplete.attachTokens(field, query -> MailApi.get().suggestContacts(query, true, 8)
                .thenApply(contacts -> {
                    Set<String> used = holder[0].completedTokens().stream()
                            .map(token -> token.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                    return contacts.stream()
                            .filter(contact -> !used.contains(contact.address().toLowerCase(Locale.ROOT)))
                            .map(contact -> toSuggestion(contact, () -> holder[0].replaceCurrent(contact.address())))
                            .toList();
                }));
        return holder[0];
    }

    /** Ô một email (lọc người gửi, thêm thành viên nhóm): chỉ gợi ý người. */
    public static AutoComplete forSinglePerson(TextField field, Runnable afterPick) {
        AutoComplete[] holder = new AutoComplete[1];
        holder[0] = AutoComplete.attach(field, 1, true, query -> MailApi.get().suggestContacts(query, false, 6)
                .thenApply(contacts -> contacts.stream()
                        .map(contact -> toSuggestion(contact, () -> {
                            holder[0].replaceCurrent(contact.address());
                            if (afterPick != null) {
                                afterPick.run();
                            }
                        }))
                        .toList()));
        return holder[0];
    }

    public static AutoComplete.Suggestion toSuggestion(Contact contact, Runnable onPick) {
        if (contact.isGroup()) {
            String address = contact.getGroupAddress() == null ? "" : contact.getGroupAddress() + "  ·  ";
            return new AutoComplete.Suggestion("Nhóm thư", Icons.GROUPS, contact.getGroupName(),
                    address + contact.getMemberCount() + " thành viên", onPick);
        }
        String name = contact.getDisplayName() == null || contact.getDisplayName().isBlank()
                ? contact.getEmail() : contact.getDisplayName();
        String subtitle = contact.getEmail()
                + (contact.getInteractions() > 0 ? "  ·  đã trao đổi " + contact.getInteractions() + " thư" : "");
        return new AutoComplete.Suggestion("Mọi người", Icons.PERSON, name, subtitle, onPick);
    }
}
