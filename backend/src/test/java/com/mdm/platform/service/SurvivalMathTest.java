package com.mdm.platform.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯函数单元测试：Kaplan-Meier / Wilson / Newcombe / log-rank。
 * 期望值按手算寿命表核对，例如 (10, 20, 30 均故障) 在 30 天 S=(2/3)*(1/2)=1/3。
 */
class SurvivalMathTest {

    @Test
    void kmWithAllEventsFollowsProductLimit() {
        List<Double> t = List.of(10.0, 20.0, 30.0);
        List<Boolean> e = List.of(true, true, true);
        // 最后一名也在 30 天故障 -> S(30)=0
        assertEquals(0.0, SurvivalMath.kmAt(t, e, 30.0).survival(), 1e-12);
        // 25 天时只发生 2 件：S=(2/3)*(1/2)=1/3
        assertEquals(1.0 / 3.0, SurvivalMath.kmAt(t, e, 25.0).survival(), 1e-9);
        assertEquals(2, SurvivalMath.kmAt(t, e, 25.0).events());
    }

    @Test
    void censorBeforeFailureKeepsSubjectAtRiskUntilCensorTime() {
        // 10 故障、20 删失：S(30)=1/2；30 天风险 = 0.5
        List<Double> t = List.of(10.0, 20.0);
        List<Boolean> e = List.of(true, false);
        SurvivalMath.KmPoint p = SurvivalMath.kmAt(t, e, 30.0);
        assertEquals(0.5, p.survival(), 1e-9);
        assertEquals(1.0 - 0.5, 1.0 - p.survival(), 1e-9);
        // 删失发生后风险集只剩 1 人，后续不再有事件，S 不变
        assertEquals(0.5, SurvivalMath.kmAt(t, e, 100.0).survival(), 1e-9);
    }

    @Test
    void noEventsYieldsSurvivalOneAndZeroEventKmCounts() {
        SurvivalMath.KmPoint p = SurvivalMath.kmAt(List.of(5.0, 9.0), List.of(false, false), 180.0);
        assertEquals(1.0, p.survival());
        assertEquals(0, p.events());
        assertEquals(2, p.censored());
    }

    @Test
    void emptySampleIsSafe() {
        SurvivalMath.KmPoint p = SurvivalMath.kmAt(List.of(), List.of(), 180.0);
        assertEquals(1.0, p.survival());
        assertEquals(0, p.sampleSize());
    }

    @Test
    void wilsonIntervalBracketsProportionAndCapsAtBounds() {
        double[] w = SurvivalMath.wilson(3, 6);
        assertTrue(w[0] < 0.5 && w[1] > 0.5);
        double[] all = SurvivalMath.wilson(5, 5);
        assertTrue(all[1] <= 1.0 + 1e-12 && all[0] < 1.0);
    }

    @Test
    void newcombeRiskDiffIsDirectional() {
        // 原件 3/6、替代件 1/6：风险差 = 1/6 - 3/6 = -1/3
        double[] d = SurvivalMath.newcombeRiskDiff(3, 6, 1, 6);
        assertEquals(-1.0 / 3.0, d[0], 1e-9);
        assertTrue(d[1] < d[0] && d[2] > d[0]);
    }

    @Test
    void logRankDetectsClearDifferenceAndIsNaWithoutEvents() {
        List<Double> t1 = List.of(10.0, 20.0, 30.0, 40.0);
        List<Boolean> e1 = List.of(true, true, true, true);
        List<Double> t2 = List.of(100.0, 110.0, 120.0, 130.0);
        List<Boolean> e2 = List.of(false, false, false, false);
        double p = SurvivalMath.logRankP(t1, e1, t2, e2);
        assertTrue(p < 0.05, "明显差异应给出显著 p 值");
        // 两组都没有事件 -> 方差为 0 -> NaN
        assertTrue(Double.isNaN(SurvivalMath.logRankP(
                List.of(5.0), List.of(false), List.of(8.0), List.of(false))));
    }

    @Test
    void logRankChiSquareMatchesP() {
        SurvivalMath.LogRankResult r = SurvivalMath.logRank(
                List.of(10.0, 20.0), List.of(true, true),
                List.of(100.0, 110.0), List.of(false, false));
        assertTrue(r.valid());
        assertEquals(SurvivalMath.chiSquare1P(r.chi2()), r.p(), 1e-12);
        assertFalse(r.chi2() < 0);
    }

    @Test
    void medianFollowUpFlipsEventFlag() {
        // 随访时间 10..40，仅 10 天那次是故障 -> 反 KM 中位随访时间应落在中间
        List<Double> t = List.of(10.0, 20.0, 30.0, 40.0);
        List<Boolean> e = List.of(true, false, false, false);
        double median = SurvivalMath.medianFollowUp(t, e);
        assertEquals(30.0, median, 1e-9);
    }
}
