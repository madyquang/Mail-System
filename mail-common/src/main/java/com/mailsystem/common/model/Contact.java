package com.mailsystem.common.model;

/**
 * Một gợi ý người nhận (SUGGEST_CONTACTS): một người (email + tên hiển thị) hoặc
 * một nhóm thư mà người dùng là thành viên (tên nhóm + số thành viên).
 */
public class Contact {

    public static final String PERSON = "PERSON";
    public static final String GROUP = "GROUP";

    private String kind;
    private String email;        // PERSON
    private String displayName;  // PERSON
    private String groupName;    // GROUP
    private int memberCount;     // GROUP
    private int interactions;    // PERSON: số thư đã trao đổi với người dùng (để xếp hạng)

    public Contact() {
    }

    public static Contact person(String email, String displayName, int interactions) {
        Contact contact = new Contact();
        contact.kind = PERSON;
        contact.email = email;
        contact.displayName = displayName;
        contact.interactions = interactions;
        return contact;
    }

    public static Contact group(String groupName, int memberCount) {
        Contact contact = new Contact();
        contact.kind = GROUP;
        contact.groupName = groupName;
        contact.memberCount = memberCount;
        return contact;
    }

    public boolean isGroup() {
        return GROUP.equals(kind);
    }

    /** Giá trị điền vào ô người nhận: email của người, hoặc tên nhóm. */
    public String address() {
        return isGroup() ? groupName : email;
    }

    public String getKind() { return kind; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getGroupName() { return groupName; }
    public int getMemberCount() { return memberCount; }
    public int getInteractions() { return interactions; }
}
