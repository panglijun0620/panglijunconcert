package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.BatchSummary;
import com.concert.analytics.api.ApiDtos.DashboardResponse;
import com.concert.analytics.api.ApiDtos.DashboardSummary;
import com.concert.analytics.api.ApiDtos.DataQualityResponse;
import com.concert.analytics.api.ApiDtos.DataQualityTotals;
import com.concert.analytics.api.ApiDtos.MarketResponse;
import com.concert.analytics.api.ApiDtos.MetricPoint;
import com.concert.analytics.api.ApiDtos.NegativeBreakdown;
import com.concert.analytics.api.ApiDtos.RiskItem;
import com.concert.analytics.api.ApiDtos.SentimentBucket;
import com.concert.analytics.api.ApiDtos.SentimentGroup;
import com.concert.analytics.api.ApiDtos.SentimentResponse;
import com.concert.analytics.api.ApiDtos.SourceCoverage;
import com.concert.analytics.api.ApiDtos.SourceQuality;
import com.concert.analytics.domain.AnalysisMetric;
import com.concert.analytics.domain.ConcertPolicy;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.domain.DataQualityMetric;
import com.concert.analytics.repository.AnalysisMetricRepository;
import com.concert.analytics.repository.CommentRepository;
import com.concert.analytics.repository.CommentRepository.SentimentSummaryProjection;
import com.concert.analytics.repository.ConcertRepository;
import com.concert.analytics.repository.DataBatchRepository;
import com.concert.analytics.repository.DataQualityMetricRepository;
import com.concert.analytics.repository.RiskInsightRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AnalyticsService {

    private static final String GLOBAL_CURRENT_BATCH = "GLOBAL_CURRENT_BATCH";
    private static final BigDecimal SCORE_MIN = new BigDecimal("-1.00");
    private static final BigDecimal SCORE_MAX = new BigDecimal("1.00");
    private static final String SOURCE_COVERAGE = "SOURCE_COVERAGE";

    /** 判定「一条评论算负面」的情感分上限，与 DtoMapper.NEGATIVE_THRESHOLD 保持一致。 */
    private static final BigDecimal RISK_SCORE_THRESHOLD = new BigDecimal("-0.20");
    /** 与 Spark 批处理保持一致：20% 起进入关注，40% 起进入重点预警。 */
    private static final BigDecimal RISK_WATCH_RATIO_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal RISK_ALERT_RATIO_THRESHOLD = new BigDecimal("0.40");
    /** 与 Spark 默认参数 risk-min-samples=20 保持一致。 */
    private static final long RISK_MIN_SAMPLE = 20;
    private static final BigDecimal RISK_TOPIC_RATIO_THRESHOLD = RISK_WATCH_RATIO_THRESHOLD;
    private static final long RISK_TOPIC_MIN_SAMPLE = RISK_MIN_SAMPLE;
    private static final int RISK_LIMIT = 5;

    private final ConcertRepository concertRepository;
    private final CommentRepository commentRepository;
    private final DataBatchRepository dataBatchRepository;
    private final AnalysisMetricRepository analysisMetricRepository;
    private final DataQualityMetricRepository dataQualityMetricRepository;
    private final RiskInsightRepository riskInsightRepository;
    private final DatasetVersionService datasetVersionService;

    public AnalyticsService(
            ConcertRepository concertRepository,
            CommentRepository commentRepository,
            DataBatchRepository dataBatchRepository,
            AnalysisMetricRepository analysisMetricRepository,
            DataQualityMetricRepository dataQualityMetricRepository,
            RiskInsightRepository riskInsightRepository,
            DatasetVersionService datasetVersionService) {
        this.concertRepository = concertRepository;
        this.commentRepository = commentRepository;
        this.dataBatchRepository = dataBatchRepository;
        this.analysisMetricRepository = analysisMetricRepository;
        this.dataQualityMetricRepository = dataQualityMetricRepository;
        this.riskInsightRepository = riskInsightRepository;
        this.datasetVersionService = datasetVersionService;
    }

    @Cacheable(cacheNames = "dashboard", key = "@datasetVersionService.currentVersion()", sync = true)
    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        List<RiskItem> risks = latest == null
                ? List.of()
                : riskInsightRepository.findByDataBatchIdOrderByNegativeRatioDesc(latest.getId()).stream()
                        .map(DtoMapper::risk)
                        .toList();
        if (risks.isEmpty()) {
            // sentiment_risk 表由 Spark 批处理；当前批次没有产出时，
            // 按主题和用户地区实时聚合同样能给出有意义的预警。
            risks = realtimeRisks(latest);
        }
        DashboardSummary summary = new DashboardSummary(
                latest == null ? 0 : concertRepository.countByDataBatchIdAndCategory(latest.getId(), "演唱会"),
                latest == null ? 0 : commentRepository.countByDataBatchId(latest.getId()),
                latest == null ? 0 : normalizedCityCount(latest),
                latest == null ? BigDecimal.ZERO : decimal(commentRepository.averageSentiment(latest.getId())),
                risks.size());
        List<MetricPoint> trend = latest == null
                ? List.of()
                : analysisMetricRepository.findByDataBatchIdOrderByMetricTypeAscDimensionKeyAsc(latest.getId()).stream()
                        .filter(metric -> "MONTH_CONCERT_COUNT".equals(metric.getMetricType()))
                        .map(this::metric)
                        .toList();
        return new DashboardResponse(
                latest == null ? "empty" : latest.getDatasetVersion(),
                summary,
                risks.stream().limit(5).toList(),
                trend,
                DtoMapper.batch(latest),
                Instant.now());
    }

    /**
     * 统计城市数时先按同一套规则清洗，再取不重复值。
     *
     * <p>原来直接数数据库里的原始 distinct 值，会把 “Unknown”（18 场）和 “演出延期”（1 场）
     * 也算成城市，导致总览显示 131 个城市、而筛选下拉框只有 128 个，同一个大屏两个数字对不上。
     * 这里改成和筛选接口完全相同的口径，两处从此不会再分叉。
     */
    private long normalizedCityCount(DataBatch batch) {
        if (batch == null) {
            return 0;
        }
        return concertRepository
                .findCitiesByDataBatchIdAndCategory(batch.getId(), ConcertPolicy.CATEGORY)
                .stream()
                .map(ConcertPolicy::normalizeCity)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    /**
     * sentiment_risk 表为空时的兜底：直接从评论表实时聚合，给出两类预警对象。
     *
     * <ul>
     *   <li><b>主题</b>：哪一类话题的负面比例明显高于整体（如票务 21.5% vs 整体 2.9%）</li>
     *   <li><b>地区</b>：哪个地区的评论负面更集中（如贵州 20.9%）</li>
     * </ul>
     *
     * <p>本批评论不关联具体演出场次；已验证的艺人范围风险由 Spark 批处理结果提供，
     * 兜底路径只按主题和用户地区实时聚合。
     */
    private List<RiskItem> realtimeRisks(DataBatch batch) {
        if (batch == null) {
            return List.of();
        }
        long batchId = batch.getId();
        List<RiskItem> candidates = new ArrayList<>();

        for (CommentRepository.ScopeNegativeProjection row
                : commentRepository.negativeByTopic(batchId, RISK_SCORE_THRESHOLD)) {
            BigDecimal ratio = ratioOf(row);
            if (row.getSampleSize() < RISK_TOPIC_MIN_SAMPLE
                    || ratio.compareTo(RISK_TOPIC_RATIO_THRESHOLD) < 0) {
                continue;
            }
            candidates.add(new RiskItem(
                    0,
                    "TOPIC",
                    row.getLabel(),
                    row.getLabel(),
                    ratio,
                    riskLevel(ratio),
                    row.getLabel(),
                    row.getSampleSize(),
                    RISK_TOPIC_RATIO_THRESHOLD,
                    RISK_TOPIC_MIN_SAMPLE,
                    riskRule(ratio, "按评论主题实时聚合"),
                    batch.getBatchId(),
                    Instant.now()));
        }

        List<CommentRepository.ScopeNegativeProjection> regions = commentRepository
                .negativeByRegion(batchId, RISK_SCORE_THRESHOLD, RISK_MIN_SAMPLE).stream()
                .filter(row -> row.getSampleSize() > 0)
                .filter(row -> ratioOf(row).compareTo(RISK_WATCH_RATIO_THRESHOLD) >= 0)
                .toList();
        if (!regions.isEmpty()) {
            Map<String, String> topTopics = topNegativeTopics(batchId, regions);
            for (CommentRepository.ScopeNegativeProjection row : regions) {
                candidates.add(new RiskItem(
                        0,
                        "REGION",
                        row.getLabel(),
                        row.getLabel(),
                        ratioOf(row),
                        riskLevel(ratioOf(row)),
                        topTopics.getOrDefault(row.getLabel(), "VENUE_SERVICE"),
                        row.getSampleSize(),
                        RISK_WATCH_RATIO_THRESHOLD,
                        RISK_MIN_SAMPLE,
                        riskRule(ratioOf(row), "按评论用户地区实时聚合"),
                        batch.getBatchId(),
                        Instant.now()));
            }
        }

        candidates.sort((left, right) -> right.negativeRatio().compareTo(left.negativeRatio()));
        List<RiskItem> items = new ArrayList<>();
        int index = 1;
        for (RiskItem item : candidates.stream().limit(RISK_LIMIT).toList()) {
            items.add(new RiskItem(index++, item.scopeType(), item.scopeId(), item.objectName(),
                    item.negativeRatio(), item.riskLevel(), item.primaryNegativeTopic(), item.sampleSize(),
                    item.threshold(), item.minimumSampleSize(), item.triggerRule(),
                    item.batchId(), item.detectedAt()));
        }
        return items;
    }

    private String riskLevel(BigDecimal ratio) {
        return ratio.compareTo(RISK_ALERT_RATIO_THRESHOLD) >= 0 ? "ALERT" : "WATCH";
    }

    /**
     * 页面带上地区或平台筛选时，按当前范围实时重算预警对象。
     *
     * <p>阈值与 Spark 批处理保持一致（20% 进入关注、40% 进入重点、样本 ≥20），
     * 区别只是把统计范围从「整批」换成「当前筛选出的这部分评论」。
     */
    private List<RiskItem> scopedRisks(DataBatch batch, String region, String platform) {
        long batchId = batch.getId();
        List<CommentRepository.ScopeNegativeProjection> artistRows = commentRepository
                .negativeByArtistScoped(batchId, region, platform, RISK_SCORE_THRESHOLD, RISK_MIN_SAMPLE).stream()
                .filter(row -> row.getSampleSize() > 0)
                .filter(row -> ratioOf(row).compareTo(RISK_WATCH_RATIO_THRESHOLD) >= 0)
                .toList();
        if (artistRows.isEmpty()) {
            return List.of();
        }

        List<String> artists = artistRows.stream()
                .map(CommentRepository.ScopeNegativeProjection::getLabel)
                .toList();
        Map<String, String> topics = new LinkedHashMap<>();
        Map<String, Long> topicCount = new LinkedHashMap<>();
        for (CommentRepository.RegionTopicProjection row
                : commentRepository.negativeTopicsByArtistScoped(
                        batchId, region, platform, RISK_SCORE_THRESHOLD, artists)) {
            if (row.getTotal() > topicCount.getOrDefault(row.getRegion(), -1L)) {
                topicCount.put(row.getRegion(), row.getTotal());
                topics.put(row.getRegion(), row.getTopic());
            }
        }

        List<RiskItem> sorted = new ArrayList<>(artistRows.size());
        for (CommentRepository.ScopeNegativeProjection row : artistRows) {
            BigDecimal ratio = ratioOf(row);
            sorted.add(new RiskItem(
                    0,
                    "ARTIST",
                    row.getLabel(),
                    row.getLabel(),
                    ratio,
                    riskLevel(ratio),
                    topics.getOrDefault(row.getLabel(), ""),
                    row.getSampleSize(),
                    RISK_WATCH_RATIO_THRESHOLD,
                    RISK_MIN_SAMPLE,
                    riskRule(ratio, "当前筛选范围"),
                    batch.getBatchId(),
                    Instant.now()));
        }
        sorted.sort((left, right) -> right.negativeRatio().compareTo(left.negativeRatio()));
        List<RiskItem> items = new ArrayList<>();
        int index = 1;
        for (RiskItem item : sorted.stream().limit(RISK_LIMIT).toList()) {
            items.add(new RiskItem(index++, item.scopeType(), item.scopeId(), item.objectName(),
                    item.negativeRatio(), item.riskLevel(), item.primaryNegativeTopic(), item.sampleSize(),
                    item.threshold(), item.minimumSampleSize(), item.triggerRule(),
                    item.batchId(), item.detectedAt()));
        }
        return items;
    }

    private String riskRule(BigDecimal ratio, String scope) {
        String boundary = ratio.compareTo(RISK_ALERT_RATIO_THRESHOLD) >= 0
                ? "负面占比 ≥ 40%（重点预警）"
                : "负面占比 ≥ 20% 且 < 40%（关注观察）";
        return boundary + "且样本 ≥ 20 条（" + scope + "）";
    }

    private Map<String, String> topNegativeTopics(
            long batchId, List<CommentRepository.ScopeNegativeProjection> rows) {
        List<String> regions = rows.stream()
                .map(CommentRepository.ScopeNegativeProjection::getLabel)
                .toList();
        Map<String, String> best = new LinkedHashMap<>();
        Map<String, Long> bestCount = new LinkedHashMap<>();
        for (CommentRepository.RegionTopicProjection row
                : commentRepository.negativeTopicsByRegion(batchId, RISK_SCORE_THRESHOLD, regions)) {
            String region = row.getRegion();
            if (row.getTotal() > bestCount.getOrDefault(region, -1L)) {
                bestCount.put(region, row.getTotal());
                best.put(region, row.getTopic());
            }
        }
        return best;
    }

    private static BigDecimal ratioOf(CommentRepository.ScopeNegativeProjection row) {
        if (row.getSampleSize() <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(row.getNegativeCount())
                .divide(BigDecimal.valueOf(row.getSampleSize()), 4, RoundingMode.HALF_UP);
    }

    @Cacheable(cacheNames = "market", key = "@datasetVersionService.currentVersion()", sync = true)
    @Transactional(readOnly = true)
    public MarketResponse market() {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return new MarketResponse(
                    "empty", GLOBAL_CURRENT_BATCH,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Instant.now());
        }
        Map<String, List<MetricPoint>> grouped = analysisMetricRepository
                .findByDataBatchIdOrderByMetricTypeAscDimensionKeyAsc(latest.getId()).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AnalysisMetric::getMetricType,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(this::metric, java.util.stream.Collectors.toList())));
        return new MarketResponse(
                latest.getDatasetVersion(),
                GLOBAL_CURRENT_BATCH,
                sanitizeCities(grouped.getOrDefault("CITY_CONCERT_COUNT", List.of())),
                sanitizeCities(grouped.getOrDefault("CITY_AVERAGE_PRICE", List.of())),
                grouped.getOrDefault("MONTH_CONCERT_COUNT", List.of()),
                grouped.getOrDefault("PRICE_CONCERT_COUNT", List.of()),
                weekdayMetrics(latest.getId(), grouped.getOrDefault("WEEKDAY_CONCERT_COUNT", List.of())),
                sanitizeLabels(grouped.getOrDefault("ARTIST_HEAT", List.of())),
                sanitizeVenues(grouped.getOrDefault("VENUE_CONCERT_COUNT", List.of())),
                Instant.now());
    }

    /**
     * 市场页"星期分布"：预聚合指标表里本来没有这一类（Spark 作业只产出 11 类指标），
     * 所以取不到时直接按明细表的演出时间现算，避免大屏上留一张空图。
     */
    private List<MetricPoint> weekdayMetrics(long dataBatchId, List<MetricPoint> aggregated) {
        if (!aggregated.isEmpty()) {
            return aggregated;
        }
        List<Object[]> rows = concertRepository.countByWeekday(dataBatchId, ConcertPolicy.CATEGORY);
        List<MetricPoint> points = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            int mysqlWeekday = ((Number) row[0]).intValue();
            BigDecimal value = BigDecimal.valueOf(((Number) row[1]).longValue());
            String label = ConcertPolicy.weekdayLabel(mysqlWeekday);
            points.add(new MetricPoint(label, label, value, "concert", null, null, value.longValue()));
        }
        return points;
    }

    /**
     * 场馆榜：源数据里混着 "· 润百颜ECM中心"、"14:30 云间剧院"、"(上元门地铁站东400米)"，
     * 清洗后可能出现重名，所以要按清洗后的名字把场次累加，再按场次从多到少排。
     */
    private List<MetricPoint> sanitizeVenues(List<MetricPoint> points) {
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        Map<String, Long> samples = new LinkedHashMap<>();
        for (MetricPoint point : points) {
            String venue = ConcertPolicy.normalizeVenue(point.label());
            if (venue == null) {
                continue;
            }
            merged.merge(venue, point.value(), BigDecimal::add);
            samples.merge(venue, point.sampleCount(), Long::sum);
        }
        List<MetricPoint> result = new ArrayList<>(merged.size());
        merged.forEach((venue, value) ->
                result.add(new MetricPoint(venue, venue, value, "concert", null, null, samples.get(venue))));
        result.sort((left, right) -> right.value().compareTo(left.value()));
        return result;
    }

    /**
     * 市场页的城市榜直接取自预聚合指标表，源数据里的 "Unknown"、"演出延期"、促销文案会被原样画进图里。
     * 这里按与筛选器相同的规则剔掉，避免大屏上出现脏城市。
     */
    private List<MetricPoint> sanitizeCities(List<MetricPoint> points) {
        return points.stream()
                .map(point -> {
                    String city = ConcertPolicy.normalizeCity(point.key());
                    return city == null ? null
                            : new MetricPoint(city, city, point.value(), point.unit(),
                                    point.periodStart(), point.periodEnd(), point.sampleCount());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** 艺人榜/场馆榜同理，主要是把没解码的 HTML 实体还原，避免 "&bull;" 直接显示出来。 */
    private List<MetricPoint> sanitizeLabels(List<MetricPoint> points) {
        return points.stream()
                .map(point -> {
                    String label = ConcertPolicy.normalizeArtist(point.label());
                    if (label == null) {
                        return null;
                    }
                    String key = ConcertPolicy.decodeHtmlEntities(point.key());
                    return new MetricPoint(key, label, point.value(), point.unit(),
                            point.periodStart(), point.periodEnd(), point.sampleCount());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Cacheable(cacheNames = "sentiment", key = "@cacheKeyService.sentiment(#region, #platform)", sync = true)
    @Transactional(readOnly = true)
    public SentimentResponse sentiment(String region, String platform) {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        BigDecimal strongNegativeThreshold = new BigDecimal("-0.60");
        BigDecimal strongPositiveThreshold = new BigDecimal("0.60");
        String normalizedRegion = region == null || region.isBlank() ? null : region.trim();
        String normalizedPlatform = normalize(platform, true);
        long totalComments = 0;
        long scored = 0;
        long stronglyNegative = 0;
        long negative = 0;
        long neutral = 0;
        long positive = 0;
        long stronglyPositive = 0;
        Double averageScore = null;
        List<SentimentGroup> bySource = List.of();
        List<SentimentGroup> topics = List.of();
        List<NegativeBreakdown> topicRisks = List.of();
        List<NegativeBreakdown> regionRisks = List.of();
        if (latest != null) {
            long batchId = latest.getId();
            totalComments = commentRepository.countSentimentScope(batchId, normalizedRegion, normalizedPlatform);
            SentimentSummaryProjection summary = commentRepository.summarizeSentiment(
                    batchId,
                    normalizedRegion,
                    normalizedPlatform,
                    strongNegativeThreshold,
                    DtoMapper.NEGATIVE_THRESHOLD,
                    DtoMapper.POSITIVE_THRESHOLD,
                    strongPositiveThreshold);
            scored = summary.getSampleSize();
            stronglyNegative = summary.getStronglyNegative();
            negative = summary.getNegative();
            neutral = summary.getNeutral();
            positive = summary.getPositive();
            stronglyPositive = summary.getStronglyPositive();
            averageScore = summary.getAverageScore();
            bySource = commentRepository.sentimentBySource(
                            batchId, normalizedRegion, normalizedPlatform).stream()
                    .map(group -> new SentimentGroup(
                            group.getLabel(), group.getSampleSize(), decimal(group.getAverageScore())))
                    .toList();
            topics = commentRepository.sentimentByTopic(
                            batchId, normalizedRegion, normalizedPlatform).stream()
                    .map(group -> new SentimentGroup(
                            group.getLabel(), group.getSampleSize(), decimal(group.getAverageScore())))
                    .toList();
            topicRisks = commentRepository
                    .negativeByTopicScoped(batchId, normalizedRegion, normalizedPlatform, RISK_SCORE_THRESHOLD).stream()
                    .filter(row -> row.getSampleSize() >= RISK_TOPIC_MIN_SAMPLE)
                    .map(row -> new NegativeBreakdown(
                            row.getLabel(), row.getSampleSize(), row.getNegativeCount(), ratioOf(row)))
                    .sorted((left, right) -> right.negativeRatio().compareTo(left.negativeRatio()))
                    .toList();
            regionRisks = commentRepository
                    .negativeByRegionScoped(
                            batchId, normalizedRegion, normalizedPlatform,
                            RISK_SCORE_THRESHOLD, RISK_MIN_SAMPLE).stream()
                    .map(row -> new NegativeBreakdown(
                            row.getLabel(), row.getSampleSize(), row.getNegativeCount(), ratioOf(row)))
                    .sorted((left, right) -> right.negativeRatio().compareTo(left.negativeRatio()))
                    .limit(10)
                    .toList();
        }
        /*
         * 风险列表也必须跟着筛选走。
         *
         * 预计算的 sentiment_risk 是对整批数据算的，一旦页面选了地区或平台，
         * 上面的情绪汇总变了、下面的预警却还是全量那几条，同一屏两个口径。
         * 所以：没筛选时用预计算结果（快、且与 Spark 产出一致）；
         * 有筛选时按同一套阈值实时重算艺人和主题两个维度。
         */
        boolean scoped = normalizedRegion != null || normalizedPlatform != null;
        List<RiskItem> risks;
        if (latest == null) {
            risks = List.of();
        } else if (scoped) {
            risks = scopedRisks(latest, normalizedRegion, normalizedPlatform);
        } else {
            risks = riskInsightRepository.findByDataBatchIdOrderByNegativeRatioDesc(latest.getId()).stream()
                    .map(DtoMapper::risk)
                    .toList();
            if (risks.isEmpty()) {
                risks = realtimeRisks(latest);
            }
        }
        return new SentimentResponse(
                latest == null ? "empty" : latest.getDatasetVersion(),
                "[-1,1]",
                DtoMapper.NEGATIVE_THRESHOLD,
                DtoMapper.POSITIVE_THRESHOLD,
                scored,
                totalComments,
                Math.max(0, totalComments - scored),
                decimal(averageScore),
                List.of(
                        new SentimentBucket("strongly_negative", SCORE_MIN, strongNegativeThreshold, stronglyNegative),
                        new SentimentBucket("negative", strongNegativeThreshold, DtoMapper.NEGATIVE_THRESHOLD, negative),
                        new SentimentBucket("neutral", DtoMapper.NEGATIVE_THRESHOLD, DtoMapper.POSITIVE_THRESHOLD, neutral),
                        new SentimentBucket("positive", DtoMapper.POSITIVE_THRESHOLD, strongPositiveThreshold, positive),
                        new SentimentBucket("strongly_positive", strongPositiveThreshold, SCORE_MAX, stronglyPositive)),
                bySource,
                topics,
                risks,
                topicRisks,
                regionRisks,
                Instant.now());
    }

    private String normalize(String value, boolean uppercase) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return uppercase ? trimmed.toUpperCase(Locale.ROOT) : trimmed.toLowerCase(Locale.ROOT);
    }

    @Cacheable(cacheNames = "data-quality", key = "@datasetVersionService.currentVersion()", sync = true)
    @Transactional(readOnly = true)
    public DataQualityResponse dataQuality() {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return new DataQualityResponse(
                    "empty", null, new DataQualityTotals(0, 0, 0, 0, 0, BigDecimal.ZERO),
                    List.of(), List.of(), List.of(), Instant.now());
        }
        List<DataQualityMetric> metrics = dataQualityMetricRepository
                .findByDataBatchIdOrderBySourceTypeAscMetricKeyAsc(latest.getId());
        List<SourceQuality> sourceMetrics = metrics.stream()
                .map(item -> new SourceQuality(
                        item.getSourceType(), item.getMetricKey(), item.getMetricValue(), item.getRecordCount(),
                        item.getMissingCount(), item.getDuplicateCount(), item.getGeneratedAt()))
                .toList();
        Map<String, Long> coverage = new LinkedHashMap<>();
        metrics.stream()
                .filter(item -> SOURCE_COVERAGE.equals(item.getMetricKey()))
                .filter(item -> !"ALL".equalsIgnoreCase(item.getSourceType()))
                .forEach(item -> coverage.put(item.getSourceType(), item.getRecordCount()));
        if (coverage.isEmpty()) {
            concertRepository.countBySource(latest.getId())
                    .forEach(item -> coverage.merge(item.getLabel(), item.getValue(), Long::sum));
            commentRepository.countBySource(latest.getId())
                    .forEach(item -> coverage.merge(item.getLabel(), item.getValue(), Long::sum));
        }
        List<SourceCoverage> sourceCoverage = coverage.entrySet().stream()
                .map(entry -> new SourceCoverage(entry.getKey(), entry.getValue()))
                .toList();
        BigDecimal successRate = latest.getInputCount() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(latest.getSuccessCount())
                        .divide(BigDecimal.valueOf(latest.getInputCount()), 4, RoundingMode.HALF_UP);
        DataQualityTotals totals = new DataQualityTotals(
                latest.getInputCount(), latest.getSuccessCount(), latest.getFailedCount(), latest.getMissingCount(),
                latest.getDuplicateCount(), successRate);
        List<BatchSummary> recentBatches = dataBatchRepository.findTop10ByOrderByStartedAtDesc().stream()
                .map(DtoMapper::batch)
                .toList();
        return new DataQualityResponse(
                latest.getDatasetVersion(), DtoMapper.batch(latest), totals, sourceCoverage, sourceMetrics,
                recentBatches, Instant.now());
    }

    private MetricPoint metric(AnalysisMetric metric) {
        return new MetricPoint(
                metric.getDimensionKey(), metric.getDimensionLabel(), metric.getMetricValue(), metric.getUnit(),
                metric.getPeriodStart(), metric.getPeriodEnd(), metric.getSampleCount());
    }

    private BigDecimal decimal(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
