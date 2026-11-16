package com.mdm.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 可靠性统计的纯函数实现（不依赖数据库，可单元测试）：
 *  - Kaplan-Meier 乘积极限与 Greenwood 方差（log-log 置信区间）
 *  - 固定随访窗口比例的 Wilson 区间
 *  - 原件/替代件风险差的 Newcombe 混合区间
 *  - 对数秩（log-rank）检验：故障尺度与删失尺度各用一次
 *  - 反 KM 中位随访时间
 *
 * 这些估计只回答“指定随访期内、同一故障定义下的风险”，
 * 删失机制是否可忽略必须另行检验（见 {@link #logRankP} 在删失尺度上的复用）。
 */
public final class SurvivalMath {

    public static final double Z_95 = 1.959963984540054;

    private SurvivalMath() {}

    public record KmPoint(double survival, double seGreenwood, double ciLow, double ciHigh,
                          int events, int censored, int sampleSize) {}

    /**
     * Kaplan-Meier 在时点 t 的生存率。
     * 删失发生在时点 t 时按“t 时刻仍在险”处理（标准约定：先风险集、后事件）。
     */
    public static KmPoint kmAt(List<Double> times, List<Boolean> events, double t) {
        int n = times.size();
        if (n == 0) {
            return new KmPoint(1.0, 0.0, 1.0, 1.0, 0, 0, 0);
        }
        TreeSet<Double> failureTimes = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            if (events.get(i) && times.get(i) <= t) {
                failureTimes.add(times.get(i));
            }
        }
        double survival = 1.0;
        double greenwood = 0.0;
        for (double ft : failureTimes) {
            int atRisk = 0;
            int d = 0;
            for (int i = 0; i < n; i++) {
                double ti = times.get(i);
                if (ti >= ft) {
                    atRisk++;
                }
                if (Double.compare(ti, ft) == 0 && events.get(i)) {
                    d++;
                }
            }
            if (atRisk == 0 || d == 0) {
                continue;
            }
            survival *= (1.0 - (double) d / atRisk);
            if (atRisk - d > 0) {
                greenwood += (double) d / ((double) atRisk * (atRisk - d));
            }
        }
        int eventsCount = 0;
        int censored = 0;
        for (int i = 0; i < n; i++) {
            if (times.get(i) <= t) {
                if (events.get(i)) {
                    eventsCount++;
                } else {
                    censored++;
                }
            }
        }
        double se = survival * Math.sqrt(greenwood);
        double[] ci = logLogInterval(survival, greenwood);
        return new KmPoint(survival, se, ci[0], ci[1], eventsCount, censored, n);
    }

    /** S(t) 的 log(-log) 置信区间；S 为 0/1 时退化为边界。 */
    static double[] logLogInterval(double survival, double greenwood) {
        if (survival <= 0.0) {
            return new double[]{0.0, 0.0};
        }
        if (survival >= 1.0 || greenwood <= 0.0) {
            return new double[]{Math.min(1.0, survival), 1.0};
        }
        double logLog = Math.log(-Math.log(survival));
        double se = Math.sqrt(greenwood) / Math.abs(Math.log(survival));
        double low = Math.exp(-Math.exp(logLog + Z_95 * se));
        double high = Math.exp(-Math.exp(logLog - Z_95 * se));
        return new double[]{Math.max(0.0, low), Math.min(1.0, high)};
    }

    /** 二项比例的 Wilson 得分区间。 */
    public static double[] wilson(int successes, int n) {
        if (n == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double z = Z_95;
        double p = (double) successes / n;
        double denom = 1.0 + z * z / n;
        double center = (p + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * (double) n * n)) / denom;
        return new double[]{Math.max(0.0, center - half), Math.min(1.0, center + half)};
    }

    /**
     * 风险差 (p2 - p1) 的 Newcombe 混合 Wilson 区间。
     * 样本为 0 时返回 NaN。
     */
    public static double[] newcombeRiskDiff(int x1, int n1, int x2, int n2) {
        if (n1 == 0 || n2 == 0) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        double p1 = (double) x1 / n1;
        double p2 = (double) x2 / n2;
        double diff = p2 - p1;
        double[] w1 = wilson(x1, n1);
        double[] w2 = wilson(x2, n2);
        double low = diff - Math.sqrt((p1 - w1[0]) * (p1 - w1[0]) + (w2[1] - p2) * (w2[1] - p2));
        double high = diff + Math.sqrt((w1[1] - p1) * (w1[1] - p1) + (p2 - w2[0]) * (p2 - w2[0]));
        return new double[]{diff, Math.max(-1.0, low), Math.min(1.0, high)};
    }

    /** KM 风险差 (risk2 - risk1) 的 Wald 区间，方差取两组 Greenwood 方差之和。 */
    public static double[] kmRiskDiff(KmPoint g1, KmPoint g2) {
        if (g1.sampleSize() == 0 || g2.sampleSize() == 0) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        double diff = (1.0 - g2.survival()) - (1.0 - g1.survival());
        double se = Math.sqrt(g1.seGreenwood() * g1.seGreenwood() + g2.seGreenwood() * g2.seGreenwood());
        return new double[]{diff, Math.max(-1.0, diff - Z_95 * se), Math.min(1.0, diff + Z_95 * se), se};
    }

    public record LogRankResult(double chi2, double p, double observedMinusExpected) {
        public boolean valid() {
            return !Double.isNaN(p);
        }
    }

    /** 两组对数秩检验（含结值处理）。 */
    public static LogRankResult logRank(List<Double> t1, List<Boolean> e1,
                                        List<Double> t2, List<Boolean> e2) {
        if (t1.isEmpty() || t2.isEmpty()) {
            return new LogRankResult(Double.NaN, Double.NaN, 0.0);
        }
        TreeSet<Double> eventTimes = new TreeSet<>();
        for (int i = 0; i < t1.size(); i++) {
            if (e1.get(i)) {
                eventTimes.add(t1.get(i));
            }
        }
        for (int i = 0; i < t2.size(); i++) {
            if (e2.get(i)) {
                eventTimes.add(t2.get(i));
            }
        }
        double observedMinusExpected = 0.0;
        double variance = 0.0;
        for (double at : eventTimes) {
            int n1 = atRisk(t1, at);
            int n2 = atRisk(t2, at);
            int d1 = eventsAt(t1, e1, at);
            int d2 = eventsAt(t2, e2, at);
            int n = n1 + n2;
            int d = d1 + d2;
            if (n <= 1 || d == 0) {
                continue;
            }
            observedMinusExpected += d1 - (double) d * n1 / n;
            variance += (double) n1 * n2 * d * (n - d) / ((double) n * n * (n - 1));
        }
        if (variance <= 0.0) {
            return new LogRankResult(Double.NaN, Double.NaN, observedMinusExpected);
        }
        double chi2 = observedMinusExpected * observedMinusExpected / variance;
        return new LogRankResult(chi2, chiSquare1P(chi2), observedMinusExpected);
    }

    /** 便捷入口：只要 p 值。 */
    public static double logRankP(List<Double> t1, List<Boolean> e1,
                                  List<Double> t2, List<Boolean> e2) {
        return logRank(t1, e1, t2, e2).p();
    }

    /** 中位事件时间（寿命表口径）：首个 S(t) <= 0.5 的时点；未达到返回 NaN。 */
    public static double medianEventTime(List<Double> times, List<Boolean> events) {
        for (double t : new TreeSet<>(times)) {
            if (kmAt(times, events, t).survival() <= 0.5) {
                return t;
            }
        }
        return Double.NaN;
    }

    /**
     * 反 KM 中位随访时间：把删失视为“事件”、故障视为删失，
     * 估计随访时间的分布中位数，比“均值随访天数”更抗极端值。
     */
    public static double medianFollowUp(List<Double> times, List<Boolean> events) {
        List<Boolean> flipped = new ArrayList<>(events.size());
        for (Boolean e : events) {
            flipped.add(!e);
        }
        return medianEventTime(times, flipped);
    }

    /** 双侧 χ²(1) 生存函数 p 值。 */
    public static double chiSquare1P(double chi2) {
        if (Double.isNaN(chi2) || chi2 < 0) {
            return Double.NaN;
        }
        return erfc(Math.sqrt(chi2 / 2.0));
    }

    private static int atRisk(List<Double> times, double at) {
        int n = 0;
        for (double t : times) {
            if (t >= at) {
                n++;
            }
        }
        return n;
    }

    private static int eventsAt(List<Double> times, List<Boolean> events, double at) {
        int n = 0;
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i) == at && events.get(i)) {
                n++;
            }
        }
        return n;
    }

    /** Abramowitz & Stegun 7.1.26，相对误差 < 1.5e-7，足够 p 值展示。 */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196
                + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? ans : 2.0 - ans;
    }
}
