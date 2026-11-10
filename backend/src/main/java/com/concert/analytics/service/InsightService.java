package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.InsightResponse;
import com.concert.analytics.domain.Comment;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.domain.RiskInsight;
import com.concert.analytics.repository.CommentRepository;
import com.concert.analytics.repository.CommentSpecifications;
import com.concert.analytics.repository.RiskInsightRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 风险对象的解读服务。
 *
 * <p>取数顺序固定为“当前批次的风险行 → 该对象的负面原话 → 生成一段解读”。
 * 生成时优先调模型，模型不可用就落到模板，两条链路输出的都是同一组数字。
 * 结果按“数据版本 + 对象名”缓存，同一批数据不会重复调用接口。
 */
@Service
public class InsightService {

    private static final int MAX_EVIDENCE = 3;

    /** 多取一些候选，优先挑短句，避免把整段长评塞进解读面板。 */
    private static final int EVIDENCE_SCAN = 40;

    private static final int QUOTE_LIMIT = 60;

    /** 模型失败后的静默时长：这段时间内直接出模板，不再逐次等待模型超时。 */
    private static final long FAILURE_SUPPRESS_MILLIS = 120_000L;

    private static final Map<String, String> TOPIC_LABELS = Map.of(
            "PERFORMANCE", "演出表现",
            "AUDIO_VISUAL", "视听效果",
            "VENUE_SERVICE", "场馆服务",
            "TICKETING", "票务",
            "PRICE", "票价",
            "TRANSPORT", "交通出行");

    private static final String SYSTEM_PROMPT = """
            你是演出市场的舆情分析助手。请只根据用户给出的统计数字和评论摘句写一段解读。
            要求：中文；80 到 140 字；一段话，不要分点，不要小标题；
            不得补充没有给出的场次、城市、金额或人名；不得编造数字；语气克制，不做定性指控。""";

    private final RiskInsightRepository riskInsightRepository;
    private final CommentRepository commentRepository;
    private final DatasetVersionService datasetVersionService;
    private final InsightCache insightCache;

    /** 模型最近一次失败的时间点 + 静默时长，早于当前时间才继续尝试模型。 */
    private volatile long suppressedUntilMillis = 0L;

    public InsightService(
            RiskInsightRepository riskInsightRepository,
            CommentRepository commentRepository,
            DatasetVersionService datasetVersionService,
            InsightCache insightCache) {
        this.riskInsightRepository = riskInsightRepository;
        this.commentRepository = commentRepository;
        this.datasetVersionService = datasetVersionService;
        this.insightCache = insightCache;
    }

    @Transactional(readOnly = true)
    public InsightResponse insight(String objectName) {
        String name = objectName == null ? "" : objectName.trim();
        if (name.isEmpty()) {
            return empty(name);
        }

        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return empty(name);
        }

        Optional<RiskInsight> risk = riskInsightRepository
                .findByDataBatchIdOrderByNegativeRatioDesc(latest.getId()).stream()
                .filter(item -> name.equals(item.getObjectName()))
                .findFirst();
        if (risk.isEmpty()) {
            return empty(name);
        }

        List<String> quotes = negativeQuotes(latest.getId(), risk.get());
        InsightFacts facts = new InsightFacts(
                name,
                risk.get().getRiskLevel(),
                risk.get().getNegativeRatio(),
                risk.get().getSampleSize(),
                negativeCount(risk.get()),
                topicLabel(risk.get().getPrimaryNegativeTopic()),
                quotes);

        String generated = modelText(name, facts);
        boolean fromModel = generated != null;
        String text = fromModel ? generated : InsightTemplate.render(facts);

        return new InsightResponse(
                name,
                risk.get().getRiskLevel(),
                risk.get().getNegativeRatio(),
                risk.get().getSampleSize(),
                risk.get().getPrimaryNegativeTopic(),
                quotes,
                text,
                fromModel ? "model" : "template",
                Instant.now());
    }

    /**
     * 取模型文字，模型不可用时返回 null。
     *
     * <p>失败后会静默一段时间，期间直接出模板：否则模型服务挂掉时，
     * 每点一次对象都要白等一次请求超时，页面上就是“点了没反应”。
     */
    private String modelText(String name, InsightFacts facts) {
        if (System.currentTimeMillis() < suppressedUntilMillis) {
            return null;
        }
        String text = insightCache.modelText(name, SYSTEM_PROMPT, userPrompt(facts));
        if (text == null) {
            suppressedUntilMillis = System.currentTimeMillis() + FAILURE_SUPPRESS_MILLIS;
        } else {
            suppressedUntilMillis = 0L;
        }
        return text;
    }

    /**
     * 取点赞最高的几条负面原话作为证据。
     *
     * <p>风险对象可能是艺人、话题或地区，按对象类型选对应的评论字段去筛；
     * 一律按艺人名查的话，话题和地区类型的风险会取不到原话。
     *
     * <p>先按点赞排序多取一批，优先选长度适中的短句；短句不够时再用长评截断补齐。
     * 这样面板上看到的是能读的原话，而不是一整段几千字的小作文。
     */
    private List<String> negativeQuotes(long batchId, RiskInsight risk) {
        String artist = null;
        String topic = null;
        String region = null;
        String scope = risk.getScopeType() == null ? "" : risk.getScopeType().trim().toUpperCase(Locale.ROOT);
        switch (scope) {
            case "TOPIC" -> topic = risk.getObjectName();
            case "REGION" -> region = risk.getObjectName();
            default -> artist = risk.getObjectName();
        }

        List<String> texts = commentRepository.findAll(
                        CommentSpecifications.filtered(
                                batchId, artist, topic, region, "NEGATIVE",
                                DtoMapper.NEGATIVE_THRESHOLD, DtoMapper.POSITIVE_THRESHOLD),
                        PageRequest.of(0, EVIDENCE_SCAN,
                                Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("commentTime"),
                                        Sort.Order.desc("id"))))
                .getContent().stream()
                .map(Comment::getCommentText)
                .filter(text -> text != null && !text.isBlank())
                .map(text -> text.replaceAll("\\s+", " ").trim())
                .distinct()
                .toList();

        List<String> selected = new java.util.ArrayList<>(texts.stream()
                .filter(text -> text.length() >= 6 && text.length() <= QUOTE_LIMIT)
                .limit(MAX_EVIDENCE)
                .toList());

        for (String text : texts) {
            if (selected.size() >= MAX_EVIDENCE) {
                break;
            }
            String clipped = clip(text);
            if (!selected.contains(clipped)) {
                selected.add(clipped);
            }
        }
        return List.copyOf(selected);
    }

    private static String clip(String text) {
        return text.length() <= QUOTE_LIMIT ? text : text.substring(0, QUOTE_LIMIT) + "…";
    }

    private long negativeCount(RiskInsight risk) {
        BigDecimal ratio = risk.getNegativeRatio();
        if (ratio == null) {
            return 0L;
        }
        return ratio.multiply(BigDecimal.valueOf(risk.getSampleSize()))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * 话题名。批处理给的是短代码，只翻译明确列出的几个；
     * 遇到 other 或未收录的值返回 null，文案里就不提话题——宁可少一句，也不要写出和原话对不上的结论。
     */
    private String topicLabel(String rawTopic) {
        return mapTopic(rawTopic);
    }

    private String mapTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return TOPIC_LABELS.get(topic.trim().toUpperCase(Locale.ROOT));
    }

    private String userPrompt(InsightFacts facts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("对象：").append(facts.objectName()).append('\n');
        prompt.append("预警级别：").append(facts.levelLabel()).append('\n');
        prompt.append("可判定评论：").append(facts.sampleSize()).append(" 条\n");
        prompt.append("负面评论：").append(facts.negativeCount()).append(" 条\n");
        prompt.append("负面占比：").append(facts.ratioPercent()).append("%\n");
        if (facts.topicLabel() != null && !facts.topicLabel().isBlank()) {
            prompt.append("负面主要话题：").append(facts.topicLabel()).append('\n');
        }
        if (!facts.quotes().isEmpty()) {
            prompt.append("代表性负面原话：");
            for (int index = 0; index < facts.quotes().size(); index++) {
                if (index > 0) {
                    prompt.append("；");
                }
                prompt.append('“').append(facts.quotes().get(index)).append('”');
            }
            prompt.append('\n');
        }
        prompt.append("请写一段解读：");
        return prompt.toString();
    }

    private InsightResponse empty(String name) {
        return new InsightResponse(name, null, null, 0L, null, List.of(), "", "none", Instant.now());
    }
}
