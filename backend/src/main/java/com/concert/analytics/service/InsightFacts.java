package com.concert.analytics.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 生成解读所需的事实集合。模板和模型提示词都用它，保证两条链路说的是同一组数字。
 */
public record InsightFacts(
        String objectName,
        String riskLevel,
        BigDecimal negativeRatio,
        long sampleSize,
        long negativeCount,
        String topicLabel,
        List<String> quotes) {

    public InsightFacts {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }

    /**
     * 负面占比的百分数，保留一位小数，用于文案与提示词。
     */
    public String ratioPercent() {
        BigDecimal ratio = negativeRatio == null ? BigDecimal.ZERO : negativeRatio;
        return ratio.multiply(BigDecimal.valueOf(100))
                .setScale(1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    public String levelLabel() {
        if ("ALERT".equalsIgnoreCase(riskLevel)) {
            return "重点预警";
        }
        if ("WATCH".equalsIgnoreCase(riskLevel)) {
            return "关注";
        }
        return "预警";
    }
}
