package com.concert.analytics.repository;

import com.concert.analytics.domain.Comment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 原始评论的下钻查询条件。
 *
 * <p>评论只按批次、艺人、主题、用户地区、情感档位筛，不关联具体演出场次——
 * 现有数据里评论与演出没有共同主键，强行按时间匹配会把“演出前就想看”的评论挂错场次。
 */
public final class CommentSpecifications {

    private CommentSpecifications() {
    }

    public static Specification<Comment> filtered(
            long dataBatchId,
            String artist,
            String topic,
            String region,
            String sentiment,
            BigDecimal negativeThreshold,
            BigDecimal positiveThreshold) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("dataBatch").get("id"), dataBatchId));

            if (hasText(artist)) {
                predicates.add(builder.equal(root.get("artistName"), artist.trim()));
            }
            if (hasText(topic)) {
                predicates.add(builder.equal(root.get("primaryTopic"), topic.trim()));
            }
            if (hasText(region)) {
                predicates.add(builder.equal(root.get("userRegion"), region.trim()));
            }

            String band = hasText(sentiment) ? sentiment.trim().toUpperCase(Locale.ROOT) : "";
            switch (band) {
                case "NEGATIVE" -> {
                    predicates.add(builder.isNotNull(root.get("sentimentScore")));
                    predicates.add(builder.lessThanOrEqualTo(root.get("sentimentScore"), negativeThreshold));
                }
                case "POSITIVE" -> {
                    predicates.add(builder.isNotNull(root.get("sentimentScore")));
                    predicates.add(builder.greaterThanOrEqualTo(root.get("sentimentScore"), positiveThreshold));
                }
                case "NEUTRAL" -> {
                    predicates.add(builder.isNotNull(root.get("sentimentScore")));
                    predicates.add(builder.greaterThan(root.get("sentimentScore"), negativeThreshold));
                    predicates.add(builder.lessThan(root.get("sentimentScore"), positiveThreshold));
                }
                case "UNSCORED" -> predicates.add(builder.isNull(root.get("sentimentScore")));
                default -> {
                    // 不传 or 传 ALL：不按情感过滤
                }
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
