package com.concert.analytics.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record PageResponse<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) implements Serializable {

        @JsonProperty("number")
        public int number() {
            return page;
        }

        public static <S, T> PageResponse<T> from(Page<S> source, Function<S, T> mapper) {
            return new PageResponse<>(
                    source.getContent().stream().map(mapper).toList(),
                    source.getNumber(),
                    source.getSize(),
                    source.getTotalElements(),
                    source.getTotalPages(),
                    source.isFirst(),
                    source.isLast());
        }
    }

    public record HealthResponse(
            String status,
            String service,
            String database,
            long concerts,
            long comments,
            Instant timestamp) implements Serializable {
    }

    public record BatchSummary(
            String batchId,
            String datasetVersion,
            String sourceType,
            String status,
            long inputCount,
            long successCount,
            long failedCount,
            long missingCount,
            long duplicateCount,
            Instant startedAt,
            Instant finishedAt,
            Instant publishedAt) implements Serializable {
    }

    public record RiskItem(
            long id,
            String scopeType,
            String scopeId,
            String objectName,
            BigDecimal negativeRatio,
            String riskLevel,
            String primaryNegativeTopic,
            long sampleSize,
            BigDecimal threshold,
            long minimumSampleSize,
            String triggerRule,
            String batchId,
            Instant detectedAt) implements Serializable {

        @JsonProperty("negativeTopic")
        public String negativeTopic() {
            return primaryNegativeTopic;
        }

        @JsonProperty("sampleCount")
        public long sampleCount() {
            return sampleSize;
        }
    }

    public record DashboardSummary(
            long concerts,
            long comments,
            long cities,
            BigDecimal averageSentiment,
            long activeRisks) implements Serializable {
    }

    public record DashboardResponse(
            String datasetVersion,
            DashboardSummary summary,
            List<RiskItem> risks,
            List<MetricPoint> trend,
            BatchSummary latestBatch,
            Instant generatedAt) implements Serializable {
    }

    public record ConcertItem(
            long id,
            String artistName,
            String category,
            String concertName,
            String city,
            String venue,
            LocalDateTime showTime,
            String showTimePrecision,
            String priceText,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String saleStatus,
            String sourceType,
            String sourceUrl,
            long commentCount) implements Serializable {
    }

    public record CommentItem(
            long id,
            String text,
            Instant commentTime,
            long likeCount,
            String userRegion,
            String sourceType,
            BigDecimal sentimentScore,
            String sentimentLabel,
            String primaryTopic) implements Serializable {
    }

    public record ConcertDetailResponse(
            ConcertItem concert,
            PageResponse<CommentItem> comments,
            PageResponse<CommentItem> artistComments,
            List<RiskItem> risks,
            String commentScopeNotice) implements Serializable {
    }

    public record ConcertFiltersResponse(
            String datasetVersion,
            List<String> cities,
            List<String> artists,
            List<String> regions,
            List<String> platforms,
            List<String> categories,
            List<String> statuses,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Instant generatedAt) implements Serializable {
    }

    public record ConcertCalendarResponse(
            String datasetVersion,
            int year,
            int month,
            List<ConcertItem> items,
            Instant generatedAt) implements Serializable {
    }

    public record MetricPoint(
            String key,
            String label,
            BigDecimal value,
            String unit,
            LocalDate periodStart,
            LocalDate periodEnd,
            long sampleCount) implements Serializable {

        @JsonProperty("name")
        public String name() {
            return label;
        }
    }

    public record MarketResponse(
            String datasetVersion,
            String aggregationScope,
            List<MetricPoint> cities,
            List<MetricPoint> cityPrices,
            List<MetricPoint> trend,
            List<MetricPoint> prices,
            List<MetricPoint> weekdays,
            List<MetricPoint> artists,
            List<MetricPoint> venues,
            Instant generatedAt) implements Serializable {
    }

    public record SentimentBucket(String label, BigDecimal lowerBound, BigDecimal upperBound, long count)
            implements Serializable {
    }

    public record SentimentGroup(String label, long sampleSize, BigDecimal averageScore)
            implements Serializable {

        @JsonProperty("count")
        public long count() {
            return sampleSize;
        }
    }

    public record SentimentResponse(
            String datasetVersion,
            String scoreScale,
            BigDecimal negativeThreshold,
            BigDecimal positiveThreshold,
            long totalSamples,
            long totalComments,
            long unscoredSamples,
            BigDecimal averageScore,
            List<SentimentBucket> distribution,
            List<SentimentGroup> bySource,
            List<SentimentGroup> topics,
            List<RiskItem> risks,
            List<NegativeBreakdown> topicRisks,
            List<NegativeBreakdown> regionRisks,
            Instant generatedAt) implements Serializable {

        @JsonProperty("sampleCount")
        public long sampleCount() {
            return totalSamples;
        }

        @JsonProperty("platforms")
        public List<SentimentGroup> platforms() {
            return bySource;
        }
    }

    /**
     * 某个对象（主题或地区）的负面占比。
     * 按主题或地区拆分规则已判定评论中的负面占比。
     */
    public record NegativeBreakdown(
            String label,
            long sampleSize,
            long negativeCount,
            BigDecimal negativeRatio) implements Serializable {

        @JsonProperty("name")
        public String name() {
            return label;
        }

        @JsonProperty("count")
        public long count() {
            return sampleSize;
        }
    }

    public record RecommendationItem(
            ConcertItem concert,
            int score,
            List<String> reasons) implements Serializable {
    }

    public record RecommendationResponse(
            String datasetVersion,
            String algorithm,
            Map<String, Object> criteria,
            List<RecommendationItem> items,
            Instant generatedAt) implements Serializable {
    }

    public record SourceQuality(
            String sourceType,
            String metricKey,
            BigDecimal metricValue,
            long recordCount,
            long missingCount,
            long duplicateCount,
            Instant generatedAt) implements Serializable {
    }

    public record SourceCoverage(String sourceType, long records) implements Serializable {

        @JsonProperty("recordCount")
        public long recordCount() {
            return records;
        }
    }

    public record DataQualityTotals(
            long inputCount,
            long successCount,
            long failedCount,
            long missingCount,
            long duplicateCount,
            BigDecimal successRate) implements Serializable {

        @JsonProperty("totalRecords")
        public long totalRecords() {
            return inputCount;
        }
    }

    public record DataQualityResponse(
            String datasetVersion,
            BatchSummary latestBatch,
            DataQualityTotals totals,
            List<SourceCoverage> sourceCoverage,
            List<SourceQuality> sourceMetrics,
            List<BatchSummary> recentBatches,
            Instant generatedAt) implements Serializable {

        @JsonProperty("summary")
        public DataQualityTotals summary() {
            return totals;
        }
    }

    public record JobItem(
            long id,
            String batchId,
            String jobType,
            String executionEngine,
            String status,
            long inputCount,
            long successCount,
            long failedCount,
            Long durationMs,
            String workerSummary,
            String message,
            Instant startedAt,
            Instant finishedAt) implements Serializable {
    }

    public record CacheClearResponse(
            List<String> cleared,
            List<String> failed,
            Instant clearedAt) implements Serializable {
    }

    /**
     * 原始评论下钻结果。
     *
     * <p>scopeNotice 用于在页面上说明统计口径：评论按艺人和用户地区聚合，未与具体演出场次建立关联。
     */
    public record CommentSearchResponse(
            String datasetVersion,
            String scopeNotice,
            long matchedSamples,
            PageResponse<CommentItem> page) implements Serializable {
    }

    /**
     * 风险对象解读结果。
     *
     * <p>source 标明这段文字从哪来：{@code model} 表示模型生成，{@code template} 表示模型不可用时的模板降级，
     * {@code none} 表示当前批次没有这个对象、页面显示“暂无解读”。text 为空时不应视为错误。
     */
    public record InsightResponse(
            String objectName,
            String riskLevel,
            BigDecimal negativeRatio,
            long sampleSize,
            String primaryNegativeTopic,
            List<String> quotes,
            String text,
            String source,
            Instant generatedAt) implements Serializable {
    }

    /**
     * 操作日志条目。
     *
     * <p>action 是动作代号（导出榜单、导出预警、导出评论、清理缓存），target 是操作对象，
     * detail 存查询条件，recordCount 是本次涉及的记录条数。
     */
    public record OperationLogItem(
            long id,
            Instant occurredAt,
            String account,
            String role,
            String action,
            String target,
            String detail,
            Long recordCount,
            String clientIp,
            boolean success) implements Serializable {
    }

    /**
     * 当前登录账号，用于页面按角色显示不同入口。
     *
     * <p>role 为 ADMIN 时显示管理入口，VIEWER 只能查看大屏。
     */
    public record AuthUser(
            String username,
            String role,
            boolean admin,
            String displayName) implements Serializable {
    }

    /**
     * 人工标注样本。
     *
     * <p>ruleLabel 是系统现有词表规则的判定结果，标注时页面先不展示，避免影响判断；
     * 标注完成后才拿它和人工结果对比。
     */
    public record AnnotationSample(
            long id,
            String text,
            String artist,
            String topic,
            java.math.BigDecimal score,
            String ruleLabel) implements Serializable {
    }
}
