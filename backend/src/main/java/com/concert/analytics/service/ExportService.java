package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.MarketResponse;
import com.concert.analytics.api.ApiDtos.MetricPoint;
import com.concert.analytics.domain.Comment;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.domain.RiskInsight;
import com.concert.analytics.export.CsvFile;
import com.concert.analytics.export.CsvWriter;
import com.concert.analytics.repository.CommentRepository;
import com.concert.analytics.repository.CommentSpecifications;
import com.concert.analytics.repository.RiskInsightRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出服务：把页面上看到的榜单、预警名单和检索结果转成 CSV。
 *
 * <p>之所以用 CSV 而不是 Excel，是因为 Excel 能直接打开 CSV，而引入 POI 要多带十几兆依赖，
 * 对一个部署在 8 核 15G 机器上的演示系统不划算。
 */
@Service
public class ExportService {

    private static final int COMMENT_LIMIT_DEFAULT = 5000;

    private static final int COMMENT_LIMIT_MAX = 20000;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final DateTimeFormatter CELL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnalyticsService analyticsService;
    private final RiskInsightRepository riskInsightRepository;
    private final CommentRepository commentRepository;
    private final DatasetVersionService datasetVersionService;

    public ExportService(
            AnalyticsService analyticsService,
            RiskInsightRepository riskInsightRepository,
            CommentRepository commentRepository,
            DatasetVersionService datasetVersionService) {
        this.analyticsService = analyticsService;
        this.riskInsightRepository = riskInsightRepository;
        this.commentRepository = commentRepository;
        this.datasetVersionService = datasetVersionService;
    }

    @Transactional(readOnly = true)
    public CsvFile market(String dimension) {
        MarketResponse market = analyticsService.market();
        String key = dimension == null ? "" : dimension.trim();
        List<MetricPoint> points;
        String title;
        switch (key) {
            case "cities" -> {
                points = market.cities();
                title = "城市演出场次";
            }
            case "cityPrices" -> {
                points = market.cityPrices();
                title = "城市平均票价";
            }
            case "trend" -> {
                points = market.trend();
                title = "月度走势";
            }
            case "prices" -> {
                points = market.prices();
                title = "票价结构";
            }
            case "weekdays" -> {
                points = market.weekdays();
                title = "星期分布";
            }
            case "venues" -> {
                points = market.venues();
                title = "场馆场次";
            }
            default -> {
                points = market.artists();
                title = "艺人热度";
            }
        }

        List<List<String>> rows = new ArrayList<>(points.size());
        int rank = 1;
        for (MetricPoint point : points) {
            rows.add(List.of(
                    String.valueOf(rank++),
                    text(point.label()),
                    number(point.value()),
                    text(point.unit()),
                    String.valueOf(point.sampleCount())));
        }
        return new CsvFile(
                filename("市场分析-" + title),
                CsvWriter.render(List.of("排名", "名称", "数值", "单位", "样本量"), rows));
    }

    @Transactional(readOnly = true)
    public CsvFile risks() {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        List<List<String>> rows = new ArrayList<>();
        if (latest != null) {
            for (RiskInsight risk : riskInsightRepository
                    .findByDataBatchIdOrderByNegativeRatioDesc(latest.getId())) {
                rows.add(List.of(
                        text(risk.getObjectName()),
                        scopeLabel(risk.getScopeType()),
                        levelLabel(risk.getRiskLevel()),
                        percent(risk.getNegativeRatio()),
                        String.valueOf(negativeCount(risk)),
                        String.valueOf(risk.getSampleSize()),
                        topicLabel(risk.getPrimaryNegativeTopic()),
                        text(risk.getTriggerRule()),
                        latest.getBatchId(),
                        time(risk.getDetectedAt())));
            }
        }
        return new CsvFile(
                filename("舆情预警名单"),
                CsvWriter.render(
                        List.of("风险对象", "对象类型", "预警等级", "负面占比", "负面条数",
                                "样本量", "主要负面话题", "触发规则", "批次号", "检出时间"),
                        rows));
    }

    @Transactional(readOnly = true)
    public CsvFile comments(String artist, String topic, String region, String sentiment, int limit) {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        List<List<String>> rows = new ArrayList<>();
        int size = limit <= 0 ? COMMENT_LIMIT_DEFAULT : Math.min(limit, COMMENT_LIMIT_MAX);
        if (latest != null) {
            PageRequest pageRequest = PageRequest.of(
                    0, size,
                    Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("commentTime"), Sort.Order.desc("id")));
            Page<Comment> page = commentRepository.findAll(
                    CommentSpecifications.filtered(
                            latest.getId(), artist, topic, region, sentiment,
                            DtoMapper.NEGATIVE_THRESHOLD, DtoMapper.POSITIVE_THRESHOLD),
                    pageRequest);
            for (Comment comment : page.getContent()) {
                rows.add(List.of(
                        time(comment.getCommentTime()),
                        text(comment.getArtistName()),
                        text(comment.getUserRegion()),
                        text(comment.getSourceType()),
                        sentimentLabel(comment.getSentimentScore()),
                        number(comment.getSentimentScore()),
                        topicLabel(comment.getPrimaryTopic()),
                        String.valueOf(comment.getLikeCount()),
                        text(comment.getCommentText())));
            }
        }
        return new CsvFile(
                filename("评论检索结果"),
                CsvWriter.render(
                        List.of("评论时间", "艺人", "用户地区", "来源", "情感判定", "情感分值",
                                "主要话题", "点赞数", "评论正文"),
                        rows));
    }

    private static long negativeCount(RiskInsight risk) {
        if (risk.getNegativeRatio() == null) {
            return 0;
        }
        return risk.getNegativeRatio()
                .multiply(BigDecimal.valueOf(risk.getSampleSize()))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private static String scopeLabel(String scopeType) {
        if (scopeType == null) {
            return "";
        }
        return switch (scopeType.toUpperCase()) {
            case "ARTIST" -> "艺人";
            case "TOPIC" -> "话题";
            case "REGION" -> "地区";
            default -> scopeType;
        };
    }

    private static String levelLabel(String level) {
        if (level == null || level.isBlank()) {
            return "未分级";
        }
        return switch (level.toUpperCase()) {
            case "ALERT" -> "重点预警";
            case "WATCH" -> "关注观察";
            default -> level;
        };
    }

    private static String sentimentLabel(BigDecimal score) {
        if (score == null) {
            return "未判定";
        }
        if (score.compareTo(DtoMapper.NEGATIVE_THRESHOLD) <= 0) {
            return "负面";
        }
        if (score.compareTo(DtoMapper.POSITIVE_THRESHOLD) >= 0) {
            return "正面";
        }
        return "中性";
    }

    private static String topicLabel(String topic) {
        if (topic == null || topic.isBlank()) {
            return "";
        }
        return switch (topic.toLowerCase()) {
            case "ticket" -> "票务";
            case "performance" -> "演出表现";
            case "service" -> "现场服务";
            case "price" -> "价格";
            case "venue" -> "场馆";
            case "other" -> "其他";
            default -> topic;
        };
    }

    private static String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "";
        }
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String time(Instant value) {
        return value == null ? "" : CELL_TIME.format(value.atZone(ZoneId.systemDefault()));
    }

    private static String filename(String base) {
        return base + "-" + FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault())) + ".csv";
    }
}
