package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.AnnotationSample;
import com.concert.analytics.domain.Comment;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.repository.CommentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 情感规则评估的抽样服务。
 *
 * <p>分层抽样：正面、中性、负面各取相同数量。这么做的原因是实际分布里正面占九成，
 * 纯随机抽 120 条大概只能碰到七八条负面，负面这一类的指标会波动得没法用。
 *
 * <p>为保证可复现：按「评论 id + 固定盐值」的哈希值排序后再等距抽取。
 * 哈希对每一行都是固定值，所以同一份数据每次抽到的样本完全一致；
 * 同时因为哈希把主键顺序彻底打散，样本分布在整个评论表上，
 * 而不是像早先那样只落在 id 最小的那一小段（那种做法抽样比例不到百分之一，代表性不足）。
 *
 * <p>系统里注水、刷屏类的超短评论一律排除，只评估有实际内容的样本。
 */
@Service
public class EvaluationService {

    private static final int MIN_TEXT_LENGTH = 6;

    /** 候选池按目标数量的倍数取，留出因文本过短被剔除的余量。 */
    private static final int CANDIDATE_FACTOR = 6;

    /**
     * 抽样用的固定盐值：和评论 id 拼起来取 CRC32，得到一个稳定的伪随机序号。
     * 换一个盐值相当于换一批样本，换了之后请同步在报告里注明。
     */
    private static final String SAMPLE_SALT = "eval-sample-2026";

    private static final List<String> BANDS = List.of("NEGATIVE", "NEUTRAL", "POSITIVE");

    private final CommentRepository commentRepository;
    private final DatasetVersionService datasetVersionService;

    public EvaluationService(CommentRepository commentRepository, DatasetVersionService datasetVersionService) {
        this.commentRepository = commentRepository;
        this.datasetVersionService = datasetVersionService;
    }

    @Transactional(readOnly = true)
    public List<AnnotationSample> samples(int perClass) {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return List.of();
        }
        List<AnnotationSample> samples = new ArrayList<>(perClass * BANDS.size());
        for (String band : BANDS) {
            samples.addAll(pick(latest.getId(), band, perClass));
        }
        return samples;
    }

    private List<AnnotationSample> pick(long batchId, String band, int take) {
        List<Comment> candidates = commentRepository.findEvaluationCandidates(
                batchId, band,
                DtoMapper.NEGATIVE_THRESHOLD, DtoMapper.POSITIVE_THRESHOLD,
                MIN_TEXT_LENGTH, SAMPLE_SALT, take * CANDIDATE_FACTOR);
        if (candidates.isEmpty()) {
            return List.of();
        }

        int step = Math.max(1, candidates.size() / take);
        List<AnnotationSample> picked = new ArrayList<>(take);
        for (int index = 0; index < take && index * step < candidates.size(); index++) {
            Comment comment = candidates.get(index * step);
            picked.add(new AnnotationSample(
                    comment.getId(),
                    DtoMapper.tidy(comment.getCommentText()),
                    comment.getArtistName(),
                    comment.getPrimaryTopic(),
                    comment.getSentimentScore(),
                    bandLabel(band)));
        }
        return picked;
    }

    private static String bandLabel(String band) {
        return switch (band) {
            case "NEGATIVE" -> "negative";
            case "POSITIVE" -> "positive";
            default -> "neutral";
        };
    }
}
