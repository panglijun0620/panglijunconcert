package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.BatchSummary;
import com.concert.analytics.api.ApiDtos.CommentItem;
import com.concert.analytics.api.ApiDtos.ConcertItem;
import com.concert.analytics.api.ApiDtos.JobItem;
import com.concert.analytics.api.ApiDtos.RiskItem;
import com.concert.analytics.domain.Comment;
import com.concert.analytics.domain.Concert;
import com.concert.analytics.domain.ConcertPolicy;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.domain.JobRun;
import com.concert.analytics.domain.RiskInsight;

import java.math.BigDecimal;
import java.time.LocalDate;

final class DtoMapper {

    static final BigDecimal NEGATIVE_THRESHOLD = new BigDecimal("-0.20");
    static final BigDecimal POSITIVE_THRESHOLD = new BigDecimal("0.20");

    private DtoMapper() {
    }

    /**
     * 展示前还原 HTML 实体。只影响接口输出，数据库里的原始值保持不动。
     *
     * <p>这里以前自带一张只有 10 条的小表，会漏掉 &amp;times;、&amp;reg;、&amp;lsquo; 这类票牛常用的实体；
     * 现在统一交给 {@link ConcertPolicy#decodeHtmlEntities(String)}，它同时支持命名实体和数字实体，
     * 认不出来的一律原样保留，不会把正常的乐队名（如 “伍佰&amp;China Blue”）改坏。
     */
    static String tidy(String value) {
        return ConcertPolicy.decodeHtmlEntities(value);
    }

    static BatchSummary batch(DataBatch batch) {
        if (batch == null) {
            return null;
        }
        return new BatchSummary(
                batch.getBatchId(), batch.getDatasetVersion(), batch.getSourceType(), batch.getStatus(),
                batch.getInputCount(), batch.getSuccessCount(), batch.getFailedCount(), batch.getMissingCount(),
                batch.getDuplicateCount(), batch.getStartedAt(), batch.getFinishedAt(), batch.getPublishedAt());
    }

    static RiskItem risk(RiskInsight risk) {
        return new RiskItem(
                risk.getId(), risk.getScopeType(), risk.getScopeId(), risk.getObjectName(), risk.getNegativeRatio(),
                risk.getRiskLevel(), risk.getPrimaryNegativeTopic(), risk.getSampleSize(), risk.getThresholdValue(),
                risk.getMinimumSampleSize(), risk.getTriggerRule(), risk.getDataBatch().getBatchId(),
                risk.getDetectedAt());
    }

    static ConcertItem concert(Concert concert, long commentCount, LocalDate today) {
        return new ConcertItem(
                concert.getId(), tidy(concert.getArtistName()), concert.getCategory(),
                tidy(concert.getConcertName()),
                concert.getCity(), tidy(concert.getVenue()), concert.getShowTime(), concert.getShowTimePrecision(), concert.getPriceText(),
                concert.getMinPrice(), concert.getMaxPrice(), ConcertPolicy.saleStatus(concert, today), concert.getSourceType(),
                concert.getSourceUrl(), commentCount);
    }

    static JobItem job(JobRun job) {
        return new JobItem(
                job.getId(), job.getDataBatch() == null ? null : job.getDataBatch().getBatchId(), job.getJobType(),
                job.getExecutionEngine(), job.getStatus(),
                job.getInputCount(), job.getSuccessCount(), job.getFailedCount(), job.getDurationMs(),
                job.getWorkerSummary(), job.getMessage(),
                job.getStartedAt(), job.getFinishedAt());
    }

    /** 原始评论下钻用的映射：只输出展示需要的字段，不带关联实体。 */
    static CommentItem comment(Comment comment) {
        return new CommentItem(
                comment.getId(), tidy(comment.getCommentText()), comment.getCommentTime(), comment.getLikeCount(),
                comment.getUserRegion(), comment.getSourceType(), comment.getSentimentScore(),
                sentimentLabel(comment.getSentimentScore()), comment.getPrimaryTopic());
    }

    static String sentimentLabel(BigDecimal score) {
        if (score == null) {
            return "unscored";
        }
        if (score.compareTo(NEGATIVE_THRESHOLD) <= 0) {
            return "negative";
        }
        if (score.compareTo(POSITIVE_THRESHOLD) >= 0) {
            return "positive";
        }
        return "neutral";
    }
}
