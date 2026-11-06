package com.concert.analytics.repository;

import com.concert.analytics.domain.Concert;
import com.concert.analytics.domain.ConcertPolicy;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ConcertSpecifications {

    private ConcertSpecifications() {
    }

    public static Specification<Concert> filtered(
            long dataBatchId,
            String query,
            String city,
            String artist,
            String category,
            String status,
            LocalDate today,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal minPrice,
            BigDecimal maxPrice) {
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("dataBatch").get("id"), dataBatchId));
            predicates.add(builder.equal(root.get("category"), ConcertPolicy.CATEGORY));
            if (hasText(query)) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("concertName")), pattern),
                        builder.like(builder.lower(root.get("artistName")), pattern),
                        builder.like(builder.lower(root.get("city")), pattern),
                        builder.like(builder.lower(root.get("venue")), pattern)));
            }
            addEqual(predicates, builder, root.get("city"), city);
            addEqual(predicates, builder, root.get("artistName"), artist);
            addEqual(predicates, builder, root.get("category"), category);
            if (ConcertPolicy.ENDED_STATUS.equals(status)) {
                predicates.add(builder.lessThan(root.get("showTime"), today.atStartOfDay()));
            } else if (hasText(status)) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("showTime"), today.atStartOfDay()));
                addEqual(predicates, builder, root.get("saleStatus"),
                        ConcertPolicy.UNVERIFIED_STATUS.equals(status)
                                ? ConcertPolicy.PURCHASE_ACTION : status);
            }
            if (startDate != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("showTime"), startDate.atStartOfDay()));
            }
            if (endDate != null) {
                predicates.add(builder.lessThan(root.get("showTime"), endDate.plusDays(1).atStartOfDay()));
            }
            if (minPrice != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("maxPrice"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("minPrice"), maxPrice));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void addEqual(
            List<Predicate> predicates,
            jakarta.persistence.criteria.CriteriaBuilder builder,
            jakarta.persistence.criteria.Path<String> path,
            String value) {
        if (hasText(value)) {
            predicates.add(builder.equal(path, value.trim()));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
