package com.mdm.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖数据库的纯单元测试：序列号解析（跨修订区间判定的基础）。
 */
class SnapshotServiceTest {

    @Test
    void parseSerialNumberExtractsDigits() {
        assertEquals(18L, SnapshotService.parseSerialNumber("SN-0018"));
        assertEquals(7L, SnapshotService.parseSerialNumber("SN-0007"));
        assertEquals(1L, SnapshotService.parseSerialNumber("SN-1"));
    }

    @Test
    void parseSerialNumberHandlesNoDigits() {
        assertEquals(0L, SnapshotService.parseSerialNumber("UNKNOWN"));
    }

    @Test
    void overlapRangeLogicHoldsForSn18() {
        // revA: SN-0001..SN-0020，revB: SN-0015..SN-0030，SN-0018 同时命中两版
        long serial = SnapshotService.parseSerialNumber("SN-0018");
        boolean inRevA = serial >= 1 && serial <= 20;
        boolean inRevB = serial >= 15 && serial <= 30;
        assertTrue(inRevA && inRevB, "SN-0018 必须同时落在 revA 与 revB 区间，触发重叠冲突");
    }
}
