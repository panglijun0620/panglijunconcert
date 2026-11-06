package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.RecommendationItem;
import com.concert.analytics.api.ApiDtos.RecommendationResponse;
import com.concert.analytics.domain.Concert;
import com.concert.analytics.domain.ConcertPolicy;
import com.concert.analytics.repository.AnalysisMetricRepository;
import com.concert.analytics.repository.ConcertRepository;
import com.concert.analytics.repository.ConcertSpecifications;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RecommendationService {

    private final ConcertRepository concertRepository;
    private final AnalysisMetricRepository analysisMetricRepository;
    private final DatasetVersionService datasetVersionService;
    private final Clock clock;

    public RecommendationService(
            ConcertRepository concertRepository,
            AnalysisMetricRepository analysisMetricRepository,
            DatasetVersionService datasetVersionService,
            Clock clock) {
        this.concertRepository = concertRepository;
        this.analysisMetricRepository = analysisMetricRepository;
        this.datasetVersionService = datasetVersionService;
        this.clock = clock;
    }

    @Cacheable(cacheNames = "recommendations", sync = true,
            key = "@cacheKeyService.recommendations(#city, #maxBudget, #artist, #sort, #limit)",
            condition = "@cacheKeyService.cacheableRecommendations(#city, #maxBudget, #artist, #sort, #limit)")
    @Transactional(readOnly = true)
    public RecommendationResponse recommend(
            String city, BigDecimal maxBudget, String artist, String sort, int limit) {
        var latest = datasetVersionService.latestBatch();
        List<Concert> candidates = latest
                .map(batch -> concertRepository.findAll(
                        ConcertSpecifications.filtered(
                                batch.getId(), null, city, artist, null, null, LocalDate.now(clock),
                                LocalDate.now(clock), null, null, maxBudget),
                        Sort.by(Sort.Order.asc("showTime"), Sort.Order.asc("id"))))
                .orElseGet(List::of)
                .stream()
                .filter(RecommendationService::isRecommendable)
                .toList();
        Map<String, Integer> heat = new LinkedHashMap<>();
        latest.ifPresent(batch -> analysisMetricRepository
                .findByDataBatchIdOrderByMetricTypeAscDimensionKeyAsc(batch.getId()).stream()
                .filter(metric -> "ARTIST_HEAT".equals(metric.getMetricType()))
                .forEach(metric -> heat.put(metric.getDimensionLabel().toLowerCase(Locale.ROOT),
                        metric.getMetricValue().min(BigDecimal.valueOf(100)).intValue())));
        List<ScoredCandidate> selected = candidates.stream()
                .map(concert -> score(concert, city, maxBudget, artist, heat))
                .sorted(comparator(sort))
                .limit(limit)
                .toList();
        List<RecommendationItem> items = selected.stream()
                .map(candidate -> new RecommendationItem(
                        DtoMapper.concert(
                                candidate.concert(), 0, LocalDate.now(clock)),
                        candidate.score(),
                        candidate.reasons()))
                .toList();
        Map<String, Object> criteria = new LinkedHashMap<>();
        if (hasText(city)) {
            criteria.put("city", city.trim());
        }
        if (maxBudget != null) {
            criteria.put("maxBudget", maxBudget);
        }
        if (hasText(artist)) {
            criteria.put("artist", artist.trim());
        }
        criteria.put("sort", sort);
        return new RecommendationResponse(
                latest.map(batch -> batch.getDatasetVersion()).orElse("empty"),
                "rule_based_rank; score is not a probability",
                criteria,
                items,
                Instant.now());
    }

    private ScoredCandidate score(
            Concert concert,
            String city,
            BigDecimal maxBudget,
            String artist,
            Map<String, Integer> heat) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (hasText(city) && concert.getCity().equalsIgnoreCase(city.trim())) {
            score += 35;
            reasons.add("匹配城市：" + concert.getCity());
        }
        if (maxBudget != null && concert.getMinPrice() != null && concert.getMinPrice().compareTo(maxBudget) <= 0) {
            score += 30;
            reasons.add("最低票价在预算内：" + concert.getMinPrice().stripTrailingZeros().toPlainString() + " 元");
        }
        if (hasText(artist) && concert.getArtistName().toLowerCase(Locale.ROOT)
                .contains(artist.trim().toLowerCase(Locale.ROOT))) {
            score += 25;
            reasons.add("匹配艺人偏好：" + concert.getArtistName());
        }
        String saleStatus = ConcertPolicy.saleStatus(concert, LocalDate.now(clock));
        if ("售票中".equals(saleStatus) || "预售".equals(saleStatus)) {
            score += 10;
            reasons.add("当前状态：" + saleStatus);
        }
        if (concert.getMinPrice() != null) {
            // 能报价的场次信息更完整，推荐时给一点权重，避免结果集中出现价格未知。
            score += 8;
            reasons.add("票价已公布：" + concert.getMinPrice().stripTrailingZeros().toPlainString()
                    + " 元起，可评估预算");
        }
        Integer heatValue = heat.get(concert.getArtistName().toLowerCase(Locale.ROOT));
        if (heatValue != null) {
            int heatPoints = Math.max(0, Math.min(20, heatValue / 5));
            score += heatPoints;
            reasons.add("艺人热度指标：" + heatValue);
        }
        if (reasons.isEmpty()) {
            reasons.add("按演出时间与当前数据批次排序");
        }
        return new ScoredCandidate(concert, score, List.copyOf(reasons));
    }

    private Comparator<ScoredCandidate> comparator(String sort) {
        Comparator<ScoredCandidate> matchOrder = Comparator.comparingInt(ScoredCandidate::score).reversed()
                .thenComparing(candidate -> candidate.concert().getShowTime())
                .thenComparing(candidate -> candidate.concert().getId());
        if ("price".equals(sort)) {
            return Comparator.comparing(
                            (ScoredCandidate candidate) -> candidate.concert().getMinPrice(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(matchOrder);
        }
        return matchOrder;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 过滤爬虫抓到的占位页：票牛的「Unknown artist」「演出延期」「city=Unknown」
     * 这类记录没有推荐价值，混进来会让首页推荐位全是无效条目。
     */
    private static boolean isRecommendable(Concert concert) {
        String artist = concert.getArtistName();
        if (artist == null || artist.isBlank() || "Unknown artist".equalsIgnoreCase(artist.trim())) {
            return false;
        }
        String city = concert.getCity();
        if (city == null || city.isBlank()
                || "Unknown".equalsIgnoreCase(city.trim())
                || "演出延期".equals(city.trim())) {
            return false;
        }
        return true;
    }

    private record ScoredCandidate(Concert concert, int score, List<String> reasons) {
    }
}
