package com.mdm.platform.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DigestUtilTest {

    @Test
    void passwordHashIsStableSha256() {
        // admin123 的固定哈希（与 seed.sql 中 app_user 一致）
        assertEquals("39258977696fccfb3adb52d5480343c42f529cc34e7fff4919b9a924bd62178c",
                DigestUtil.passwordHash("admin123"));
        assertEquals(64, DigestUtil.passwordHash("anything").length());
    }

    @Test
    void differentPasswordsHaveDifferentHash() {
        assertNotEquals(DigestUtil.passwordHash("admin123"), DigestUtil.passwordHash("eng123"));
    }
}
