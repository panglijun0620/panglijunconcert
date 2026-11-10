package com.concert.analytics;

import com.concert.analytics.service.InsightFacts;
import com.concert.analytics.service.InsightTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板解读只复述给定事实：数字、级别、话题、原话都要出现，且不能凭空补内容。
 */
class InsightTemplateTest {

    @Test
    void rendersNumbersAndQuotesFromFacts() {
        InsightFacts facts = new InsightFacts(
                "孙露", "ALERT", new BigDecimal("0.7347"), 49, 36, "演出表现",
                List.of("半场假唱，观众喊退票", "音响太差了"));

        String text = InsightTemplate.render(facts);

        assertThat(text).contains("孙露");
        assertThat(text).contains("49 条可判定评论");
        assertThat(text).contains("36 条为负面");
        assertThat(text).contains("73.5%");
        assertThat(text).contains("重点预警");
        assertThat(text).contains("演出表现");
        assertThat(text).contains("半场假唱，观众喊退票");
    }

    @Test
    void degradesWhenNoQuotesAvailable() {
        InsightFacts facts = new InsightFacts(
                "某组合", "WATCH", new BigDecimal("0.2500"), 40, 10, "票务", List.of());

        String text = InsightTemplate.render(facts);

        assertThat(text).contains("关注");
        assertThat(text).contains("25.0%");
        assertThat(text).contains("没有取到可引用的负面原话");
    }
}
