package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.CommentItem;
import com.concert.analytics.api.ApiDtos.CommentSearchResponse;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.domain.Comment;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.repository.CommentRepository;
import com.concert.analytics.repository.CommentSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 原始评论下钻服务。
 *
 * <p>页面上点击某个负面主题、某个地区或某个风险对象时，用它取回对应的真实评论，
 * 让“负面占比 59%”这类结论能落到具体句子。排序按点赞数优先，让代表性评论排前面。
 */
@Service
public class CommentService {

    /** 与页面口径保持一致：评论只按批次、艺人、主题、地区、情感档位筛选，不关联具体场次。 */
    private static final String SCOPE_NOTICE =
            "评论按艺人与用户地区聚合，未与具体演出场次建立关联；情感为词典规则判定结果，未判定样本不参与占比。";

    private static final int MAX_PAGE_SIZE = 50;

    private final CommentRepository commentRepository;
    private final DatasetVersionService datasetVersionService;

    public CommentService(CommentRepository commentRepository, DatasetVersionService datasetVersionService) {
        this.commentRepository = commentRepository;
        this.datasetVersionService = datasetVersionService;
    }

    @Transactional(readOnly = true)
    public CommentSearchResponse search(
            String artist,
            String topic,
            String region,
            String sentiment,
            int page,
            int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageRequest = PageRequest.of(
                safePage, safeSize,
                Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("commentTime"), Sort.Order.desc("id")));

        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return new CommentSearchResponse(
                    "empty", SCOPE_NOTICE, 0, new PageResponse<>(List.of(), safePage, safeSize, 0, 0, true, true));
        }

        Page<Comment> result = commentRepository.findAll(
                CommentSpecifications.filtered(
                        latest.getId(), artist, topic, region, sentiment,
                        DtoMapper.NEGATIVE_THRESHOLD, DtoMapper.POSITIVE_THRESHOLD),
                pageRequest);

        return new CommentSearchResponse(
                latest.getDatasetVersion(),
                SCOPE_NOTICE,
                result.getTotalElements(),
                new PageResponse<>(
                        result.getContent().stream().map(DtoMapper::comment).toList(),
                        result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                        result.isFirst(), result.isLast()));
    }
}
