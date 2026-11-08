// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

/**
 * Một gợi ý người nhận (SUGGEST_CONTACTS): một người (email + tên hiển thị) hoặc
 * một nhóm thư mà người dùng là thành viên (tên nhóm + số thành viên).
 */
public class Contact {

    // Hai giá trị của trường kind: gợi ý là một người hoặc một nhóm.
    public static final String PERSON = "PERSON";
    public static final String GROUP = "GROUP";

    // Loại gợi ý: PERSON hoặc GROUP.
    private String kind;
    private String email;        // PERSON
    private String displayName;  // PERSON
    private String groupName;    // GROUP
    private String groupAddress; // GROUP: <ten-nhom>@group.local
    private int memberCount;     // GROUP
    private int interactions;    // PERSON: số thư đã trao đổi với người dùng (để xếp hạng)

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public Contact() {
    }

    /**
     * Tạo gợi ý loại "người".
     *
     * @param email        email người đó
     * @param displayName  tên hiển thị
     * @param interactions số thư đã trao đổi (càng lớn càng được xếp trước)
     * @return đối tượng Contact loại PERSON
     */
    public static Contact person(String email, String displayName, int interactions) {
        // Tạo đối tượng rỗng rồi gán từng trường (các trường nhóm để mặc định).
        Contact contact = new Contact();
        contact.kind = PERSON;
        contact.email = email;
        contact.displayName = displayName;
        contact.interactions = interactions;
        return contact;
    }

    /**
     * Tạo gợi ý loại "nhóm".
     *
     * @param groupName    tên nhóm
     * @param groupAddress địa chỉ nhóm (có thể null nếu nhóm cũ chưa có địa chỉ)
     * @param memberCount  số thành viên
     * @return đối tượng Contact loại GROUP
     */
    public static Contact group(String groupName, String groupAddress, int memberCount) {
        // Tạo đối tượng rỗng rồi gán từng trường (các trường người để mặc định).
        Contact contact = new Contact();
        contact.kind = GROUP;
        contact.groupName = groupName;
        contact.groupAddress = groupAddress;
        contact.memberCount = memberCount;
        return contact;
    }

    /** @return true nếu gợi ý này là một nhóm */
    public boolean isGroup() {
        // So sánh hằng trước để không lỗi khi kind là null.
        return GROUP.equals(kind);
    }

    /** Giá trị điền vào ô người nhận: email của người, hoặc địa chỉ nhóm (tên nhóm nếu chưa có địa chỉ). */
    public String address() {
        // Gợi ý là người: điền email.
        if (!isGroup()) {
            return email;
        }
        // Gợi ý là nhóm: ưu tiên địa chỉ nhóm, nhóm cũ chưa có địa chỉ thì điền tên nhóm.
        return groupAddress == null || groupAddress.isBlank() ? groupName : groupAddress;
    }

    // Các getter.
    public String getKind() { return kind; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getGroupName() { return groupName; }
    public String getGroupAddress() { return groupAddress; }
    public int getMemberCount() { return memberCount; }
    public int getInteractions() { return interactions; }
}
