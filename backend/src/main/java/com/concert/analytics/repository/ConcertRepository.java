package com.concert.analytics.repository;

import com.concert.analytics.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ConcertRepository extends JpaRepository<Concert, Long>, JpaSpecificationExecutor<Concert> {

    long countByDataBatchId(long dataBatchId);

    long countByDataBatchIdAndCategory(long dataBatchId, String category);

    Optional<Concert> findByIdAndDataBatchId(long id, long dataBatchId);

    Optional<Concert> findByIdAndDataBatchIdAndCategory(long id, long dataBatchId, String category);

    @Query("select count(distinct c.city) from Concert c where c.dataBatch.id = :dataBatchId")
    long countDistinctCitiesByDataBatchId(@Param("dataBatchId") long dataBatchId);

    @Query("select count(distinct c.city) from Concert c where c.dataBatch.id = :dataBatchId and c.category = :category")
    long countDistinctCitiesByDataBatchIdAndCategory(
            @Param("dataBatchId") long dataBatchId, @Param("category") String category);

    @Query("select distinct c.city from Concert c where c.dataBatch.id = :dataBatchId and c.category = :category order by c.city")
    List<String> findCitiesByDataBatchIdAndCategory(
            @Param("dataBatchId") long dataBatchId, @Param("category") String category);

    @Query("select distinct c.artistName from Concert c where c.dataBatch.id = :dataBatchId and c.category = :category order by c.artistName")
    List<String> findArtistsByDataBatchIdAndCategory(
            @Param("dataBatchId") long dataBatchId, @Param("category") String category);

    @Query("""
            select distinct c.saleStatus from Concert c
            where c.dataBatch.id = :dataBatchId and c.category = :category and c.showTime >= :today
            order by c.saleStatus
            """)
    List<String> findUpcomingStatusesByDataBatchIdAndCategory(
            @Param("dataBatchId") long dataBatchId,
            @Param("category") String category,
            @Param("today") java.time.LocalDateTime today);

    @Query("""
            select count(c) from Concert c
            where c.dataBatch.id = :dataBatchId and c.category = :category and c.showTime < :today
            """)
    long countEndedByDataBatchIdAndCategory(
            @Param("dataBatchId") long dataBatchId,
            @Param("category") String category,
            @Param("today") java.time.LocalDateTime today);

    @Query("""
            select min(coalesce(c.minPrice, c.maxPrice)) as minPrice,
                   max(coalesce(c.maxPrice, c.minPrice)) as maxPrice
            from Concert c
            where c.dataBatch.id = :dataBatchId and c.category = :category
            """)
    PriceBoundsProjection findPriceBoundsByDataBatchIdAndCategory(
            @Param("dataBatchId") long dataBatchId, @Param("category") String category);

    @Query("""
            select c.sourceType as label, count(c) as value
            from Concert c
            where c.dataBatch.id = :dataBatchId
            group by c.sourceType
            order by count(c) desc
            """)
    List<SourceCountProjection> countBySource(@Param("dataBatchId") long dataBatchId);

    /**
     * 按星期统计演出场次，供市场页"星期分布"使用。
     * 预聚合指标表里没有这一类指标（Spark 作业只产出了 11 类），所以直接从明细表现算。
     * MySQL 的 WEEKDAY() 返回 0=周一 … 6=周日，和页面标签顺序一致。
     */
    @Query(value = """
            select weekday(show_time) as dow, count(*) as value
            from clean_concert
            where data_batch_id = :dataBatchId and category = :category
            group by weekday(show_time)
            order by dow
            """, nativeQuery = true)
    List<Object[]> countByWeekday(
            @Param("dataBatchId") long dataBatchId, @Param("category") String category);

    interface PriceBoundsProjection {
        BigDecimal getMinPrice();

        BigDecimal getMaxPrice();
    }
}
