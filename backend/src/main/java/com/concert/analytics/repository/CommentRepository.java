package com.concert.analytics.repository;

import com.concert.analytics.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

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
