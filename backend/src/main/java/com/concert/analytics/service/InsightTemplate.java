package com.concert.analytics.service;

import java.util.List;
import java.util.StringJoiner;

/**
 * 模板解读。
 *
 * <p>不依赖任何外部服务：模型不可用、断网、额度用完时，页面上的文字就由它生成。
 * 文案只复述已有的统计数字和原始评论摘句，不做推测，也不编造场次和金额。
 */
public final class InsightTemplate {

    private InsightTemplate() {
    }

    public static String render(InsightFacts facts) {
        StringBuilder text = new StringBuilder();
        text.append(facts.objectName())
                .append(" 在当前批次共有 ")
                .append(facts.sampleSize())
                .append(" 条可判定评论，其中 ")
                .append(facts.negativeCount())
                .append(" 条为负面，负面占比 ")
                .append(facts.ratioPercent())
                .append("%，属于")
                .append(facts.levelLabel())
                .append("。");

        if (facts.topicLabel() != null && !facts.topicLabel().isBlank()) {
            text.append("负面言论主要集中在").append(facts.topicLabel()).append("，");
        }

        List<String> quotes = facts.quotes();
        if (!quotes.isEmpty()) {
            StringJoiner joiner = new StringJoiner("”“", "代表原话如“", "”。");
            quotes.forEach(quote -> joiner.add(clip(quote)));
            text.append(joiner);
        } else {
            text.append("当前批次没有取到可引用的负面原话。");
        }

        text.append("建议以这些原始评论为线索核对具体场次，确认问题是否集中在某一站。");
        return text.toString();
    }

    private static String clip(String quote) {
        String cleaned = quote == null ? "" : quote.replaceAll("\\s+", " ").trim();
        // 原话自带句末标点时先去掉，避免出现“…一次。”。建议…”这种双句号
        cleaned = cleaned.replaceAll("[。．.!！?？，,；;、]+$", "");
        if (cleaned.length() <= 30) {
            return cleaned;
        }
        return cleaned.substring(0, 30) + "…";
    }
}
