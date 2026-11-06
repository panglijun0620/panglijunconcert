package com.concert.analytics.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.StringJoiner;

@Component("cacheKeyService")
public class CacheKeyService {

    private final DatasetVersionService datasetVersionService;
    private final Clock clock;

    public CacheKeyService(DatasetVersionService datasetVersionService, Clock clock) {
        this.datasetVersionService = datasetVersionService;
        this.clock = clock;
    }

    public String sentiment(String region, String platform) {
        return new StringJoiner(":")
                .add(datasetVersionService.currentVersion())
                .add(normalizeInsensitive(region))
                .add(normalizeInsensitive(platform))
                .toString();
    }

    public String recommendations(String city, BigDecimal maxBudget, String artist, String sort, int limit) {
        return new StringJoiner(":")
                .add(datasetVersionService.currentVersion())
                .add(normalizeExact(city))
                .add(maxBudget == null ? "" : maxBudget.stripTrailingZeros().toPlainString())
                .add(normalizeExact(artist))
                .add(normalizeInsensitive(sort))
                .add(Integer.toString(limit))
                .add(LocalDate.now(clock).toString())
                .toString();
    }

    public boolean cacheableRecommendations(
            String city, BigDecimal maxBudget, String artist, String sort, int limit) {
        return normalizeExact(city).isEmpty()
                && maxBudget == null
                && normalizeExact(artist).isEmpty()
                && "match".equals(normalizeInsensitive(sort))
                && limit == 8;
    }

    private String normalizeInsensitive(String value) {
        return normalizeExact(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeExact(String value) {
        return value == null ? "" : value.trim();
    }
}
