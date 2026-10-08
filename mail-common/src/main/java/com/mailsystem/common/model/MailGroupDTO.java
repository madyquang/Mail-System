package com.mailsystem.common.model;

import java.util.List;

/** DTO cho mail_group + danh sách thành viên (email). */
public class MailGroupDTO {
    private int groupId;
    private String groupName;
    private String ownerEmail;
    private List<String> memberEmails;
    private boolean owner;

    public MailGroupDTO() {
    }

    public int getGroupId() { return groupId; }
    public void setGroupId(int groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    public List<String> getMemberEmails() { return memberEmails; }
    public void setMemberEmails(List<String> memberEmails) { this.memberEmails = memberEmails; }

    public boolean isOwner() { return owner; }
    public void setOwner(boolean owner) { this.owner = owner; }
}
