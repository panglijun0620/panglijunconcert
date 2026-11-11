package com.concert.analytics.repository;

import com.concert.analytics.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long>, JpaSpecificationExecutor<Comment> {

    long countByDataBatchId(long dataBatchId);

    /**
     * 舆情筛选用的地区选项。评论不直接绑定具体场次，艺人范围评论仍可按艺人范围聚合。
     */
    @Query("""
            select c.userRegion as label, count(c) as value
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.userRegion is not null
              and c.userRegion <> ''
              and c.userRegion <> '中国'
            group by c.userRegion
            having count(c) >= :minimumSampleSize
            order by count(c) desc
            """)
    List<SourceCountProjection> findRegions(
            @Param("dataBatchId") long dataBatchId,
            @Param("minimumSampleSize") long minimumSampleSize);

    @Query("""
            select coalesce(avg(c.sentimentScore), 0)
            from Comment c
            where c.dataBatch.id = :dataBatchId and c.sentimentScore is not null
            """)
    Double averageSentiment(@Param("dataBatchId") long dataBatchId);

    @Query("""
            select count(c) as sampleSize,
                   coalesce(avg(c.sentimentScore), 0) as averageScore,
                   coalesce(sum(case when c.sentimentScore < :strongNegative then 1 else 0 end), 0)
                       as stronglyNegative,
                   coalesce(sum(case when c.sentimentScore >= :strongNegative
                                          and c.sentimentScore <= :negative then 1 else 0 end), 0)
                       as negative,
                   coalesce(sum(case when c.sentimentScore > :negative
                                          and c.sentimentScore < :positive then 1 else 0 end), 0)
                       as neutral,
                   coalesce(sum(case when c.sentimentScore >= :positive
                                          and c.sentimentScore < :strongPositive then 1 else 0 end), 0)
                       as positive,
                   coalesce(sum(case when c.sentimentScore >= :strongPositive then 1 else 0 end), 0)
                       as stronglyPositive
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            """)
    SentimentSummaryProjection summarizeSentiment(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("strongNegative") BigDecimal strongNegative,
            @Param("negative") BigDecimal negative,
            @Param("positive") BigDecimal positive,
            @Param("strongPositive") BigDecimal strongPositive);

    @Query("""
            select count(c)
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            """)
    long countSentimentScope(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType);

    @Query("""
            select c.sourceType as label, count(c) as sampleSize, coalesce(avg(c.sentimentScore), 0) as averageScore
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            group by c.sourceType
            order by count(c) desc, c.sourceType asc
            """)
    List<SentimentGroupProjection> sentimentBySource(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType);

    @Query("""
            select c.primaryTopic as label, count(c) as sampleSize, coalesce(avg(c.sentimentScore), 0) as averageScore
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.primaryTopic is not null
              and c.sentimentScore is not null
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            group by c.primaryTopic
            order by count(c) desc, c.primaryTopic asc
            """)
    List<SentimentGroupProjection> sentimentByTopic(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType);

    @Query("""
            select c.sourceType as label, count(c) as value
            from Comment c
            where c.dataBatch.id = :dataBatchId
            group by c.sourceType
            order by count(c) desc
            """)
    List<SourceCountProjection> countBySource(@Param("dataBatchId") long dataBatchId);

    /**
     * 按评论用户地区聚合负面占比。
     * 评论不关联具体演出场次；可验证的艺人范围由批处理风险结果单独聚合。
     */
    @Query("""
            select c.userRegion as label, count(c) as sampleSize,
                   coalesce(sum(case when c.sentimentScore <= :negative then 1 else 0 end), 0) as negativeCount
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and c.userRegion is not null
              and c.userRegion <> ''
            group by c.userRegion
            having count(c) >= :minimumSampleSize
            """)
    List<ScopeNegativeProjection> negativeByRegion(
            @Param("dataBatchId") long dataBatchId,
            @Param("negative") BigDecimal negative,
            @Param("minimumSampleSize") long minimumSampleSize);

    /**
     * 按评论主题聚合负面占比，用于「哪些话题最招骂」。
     */
    @Query("""
            select c.primaryTopic as label, count(c) as sampleSize,
                   coalesce(sum(case when c.sentimentScore <= :negative then 1 else 0 end), 0) as negativeCount
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and c.primaryTopic is not null
              and c.primaryTopic <> ''
            group by c.primaryTopic
            """)
    List<ScopeNegativeProjection> negativeByTopic(
            @Param("dataBatchId") long dataBatchId,
            @Param("negative") BigDecimal negative);

    /**
     * 按评论主题聚合负面占比，并跟随页面上的地区/平台筛选。
     *
     * <p>原来的 {@link #negativeByTopic} 只按批次聚合，页面选了地区之后上方的情绪汇总变了、
     * 下方的主题风险却纹丝不动，同一屏里两个数字对不上。舆情页统一改用本方法。
     */
    @Query("""
            select c.primaryTopic as label, count(c) as sampleSize,
                   coalesce(sum(case when c.sentimentScore <= :negative then 1 else 0 end), 0) as negativeCount
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and c.primaryTopic is not null
              and c.primaryTopic <> ''
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            group by c.primaryTopic
            """)
    List<ScopeNegativeProjection> negativeByTopicScoped(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("negative") BigDecimal negative);

    /** 按评论用户地区聚合负面占比，同样跟随地区/平台筛选。 */
    @Query("""
            select c.userRegion as label, count(c) as sampleSize,
                   coalesce(sum(case when c.sentimentScore <= :negative then 1 else 0 end), 0) as negativeCount
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and c.userRegion is not null
              and c.userRegion <> ''
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            group by c.userRegion
            having count(c) >= :minimumSampleSize
            """)
    List<ScopeNegativeProjection> negativeByRegionScoped(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("negative") BigDecimal negative,
            @Param("minimumSampleSize") long minimumSampleSize);

    /**
     * 按艺人聚合负面占比，并跟随筛选条件。
     *
     * <p>筛选之后不能再直接沿用预计算的 sentiment_risk 表——那张表是对整批数据算的，
     * 和当前筛选范围不是一回事。这里按同一套阈值（负面占比 40%/20%、样本 ≥20）实时重算。
     */
    @Query("""
            select c.artistName as label, count(c) as sampleSize,
                   coalesce(sum(case when c.sentimentScore <= :negative then 1 else 0 end), 0) as negativeCount
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore is not null
              and c.artistName is not null
              and c.artistName <> ''
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            group by c.artistName
            having count(c) >= :minimumSampleSize
            """)
    List<ScopeNegativeProjection> negativeByArtistScoped(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("negative") BigDecimal negative,
            @Param("minimumSampleSize") long minimumSampleSize);

    /**
     * 情感规则评估的候选池：按「id + 固定盐值」的 CRC32 排序，把主键顺序彻底打散后再取前 N 条。
     *
     * <p>用原生查询是因为排序表达式走不了 JPA 的属性路径解析——
     * 传 JpaSort 会被当成实体属性名，直接抛 PropertyReferenceException。
     *
     * <p>哈希对每一行都是固定值，所以同一份数据每次抽到的候选池完全一致（可复现）；
     * 同时因为打散过，候选池覆盖整张评论表，而不是像早先那样只落在 id 最小的那一小段。
     *
     * @param band     NEGATIVE / POSITIVE / NEUTRAL
     * @param salt     抽样盐值，换一个字符串相当于换一批样本
     */
    @Query(value = """
            SELECT * FROM clean_comment
            WHERE data_batch_id = :dataBatchId
              AND comment_text IS NOT NULL
              AND CHAR_LENGTH(TRIM(comment_text)) >= :minTextLength
              AND sentiment_score IS NOT NULL
              AND (
                    (:band = 'NEGATIVE' AND sentiment_score <= :negative)
                 OR (:band = 'POSITIVE' AND sentiment_score >= :positive)
                 OR (:band = 'NEUTRAL' AND sentiment_score > :negative AND sentiment_score < :positive)
              )
            ORDER BY CRC32(CONCAT(id, :salt))
            LIMIT :candidateSize
            """, nativeQuery = true)
    List<Comment> findEvaluationCandidates(
            @Param("dataBatchId") long dataBatchId,
            @Param("band") String band,
            @Param("negative") BigDecimal negative,
            @Param("positive") BigDecimal positive,
            @Param("minTextLength") int minTextLength,
            @Param("salt") String salt,
            @Param("candidateSize") int candidateSize);

    /**
     * 指定艺人里，负面评论各主题的条数，用来补上预警条目的「主要负面话题」。
     *
     * <p>返回结构复用 {@link RegionTopicProjection}，其中 {@code region} 装的是艺人名。
     */
    @Query("""
            select c.artistName as region, c.primaryTopic as topic, count(c) as total
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore <= :negative
              and c.artistName in :artists
              and c.primaryTopic is not null
              and c.primaryTopic <> ''
              and (:region is null or c.userRegion = :region)
              and (:sourceType is null or upper(c.sourceType) = :sourceType)
            group by c.artistName, c.primaryTopic
            """)
    List<RegionTopicProjection> negativeTopicsByArtistScoped(
            @Param("dataBatchId") long dataBatchId,
            @Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("negative") BigDecimal negative,
            @Param("artists") List<String> artists);

    /**
     * 指定地区里，负面评论各主题的条数，用来找出该地区最突出的负面话题。
     */
    @Query("""
            select c.userRegion as region, c.primaryTopic as topic, count(c) as total
            from Comment c
            where c.dataBatch.id = :dataBatchId
              and c.sentimentScore <= :negative
              and c.userRegion in :regions
              and c.primaryTopic is not null
              and c.primaryTopic <> ''
            group by c.userRegion, c.primaryTopic
            """)
    List<RegionTopicProjection> negativeTopicsByRegion(
            @Param("dataBatchId") long dataBatchId,
            @Param("negative") BigDecimal negative,
            @Param("regions") List<String> regions);

    interface SentimentSummaryProjection {
        long getSampleSize();

        Double getAverageScore();

        long getStronglyNegative();

        long getNegative();

        long getNeutral();

        long getPositive();

        long getStronglyPositive();
    }

    interface SentimentGroupProjection {
        String getLabel();

        long getSampleSize();

        Double getAverageScore();
    }

    interface ScopeNegativeProjection {
        String getLabel();

        long getSampleSize();

        long getNegativeCount();
    }

    interface RegionTopicProjection {
        String getRegion();

        String getTopic();

        long getTotal();
    }
}
