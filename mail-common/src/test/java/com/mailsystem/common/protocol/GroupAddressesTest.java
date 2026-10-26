package com.mailsystem.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupAddressesTest {

    @Test
    void slugStripsVietnameseMarksAndSymbols() {
        assertEquals("lop-ltm-2026", GroupAddresses.slug("Lớp LTM 2026"));
        assertEquals("do-an-nhom-dac-biet", GroupAddresses.slug("  Đồ án — nhóm ĐẶC BIỆT!! "));
        assertEquals("nhom", GroupAddresses.slug("@@@"), "tên toàn ký tự lạ vẫn ra địa chỉ hợp lệ");
        assertTrue(GroupAddresses.slug("a".repeat(200)).length() <= GroupAddresses.MAX_LOCAL_PART);
    }

    @Test
    void localPartValidationAcceptsOptionalDomain() {
        assertEquals("lop-ltm", GroupAddresses.normalizeLocalPart("Lop-LTM@group.local"));
        assertEquals("ltm.k7", GroupAddresses.normalizeLocalPart("ltm.k7"));
        assertNull(GroupAddresses.normalizeLocalPart("lớp ltm"), "có dấu / khoảng trắng bị từ chối");
        assertNull(GroupAddresses.normalizeLocalPart("-abc"));
        assertTrue(GroupAddresses.isGroupAddress("Lop-Ltm@Group.Local"));
        assertFalse(GroupAddresses.isGroupAddress("an@mail.local"));
    }
}
