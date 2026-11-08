// Gói "model": các lớp dữ liệu (DTO) được chuyển thành JSON để gửi giữa Client và Server.
package com.mailsystem.common.model;

// List: danh sách có thứ tự.
import java.util.List;

/** DTO cho mail_group + danh sách thành viên (email). Server trả về cho lệnh GET_MY_GROUPS. */
public class MailGroupDTO {
    // Mã nhóm (khoá chính group_id).
    private int groupId;
    // Tên nhóm, vd "Lớp LTM".
    private String groupName;
    private String groupAddress;    // <ten-nhom>@group.local
    // Email của chủ nhóm (người tạo).
    private String ownerEmail;
    // Email của các thành viên.
    private List<String> memberEmails;
    // true nếu người đang xem chính là chủ nhóm.
    private boolean owner;

    /** Constructor rỗng: Gson cần để tạo đối tượng khi giải mã JSON. */
    public MailGroupDTO() {
    }

    // Getter / setter mã nhóm.
    public int getGroupId() { return groupId; }
    public void setGroupId(int groupId) { this.groupId = groupId; }

    // Getter / setter tên nhóm.
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    // Getter / setter địa chỉ nhóm.
    public String getGroupAddress() { return groupAddress; }
    public void setGroupAddress(String groupAddress) { this.groupAddress = groupAddress; }

    // Getter / setter email chủ nhóm.
    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    // Getter / setter danh sách email thành viên.
    public List<String> getMemberEmails() { return memberEmails; }
    public void setMemberEmails(List<String> memberEmails) { this.memberEmails = memberEmails; }

    // Getter / setter "người xem có phải chủ nhóm".
    public boolean isOwner() { return owner; }
    public void setOwner(boolean owner) { this.owner = owner; }
}
