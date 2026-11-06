package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.CommentItem;
import com.concert.analytics.api.ApiDtos.ConcertCalendarResponse;
import com.concert.analytics.api.ApiDtos.ConcertDetailResponse;
import com.concert.analytics.api.ApiDtos.ConcertFiltersResponse;
import com.concert.analytics.api.ApiDtos.ConcertItem;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.domain.Concert;
import com.concert.analytics.domain.ConcertPolicy;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.error.NotFoundException;
import com.concert.analytics.repository.CommentRepository;
import com.concert.analytics.repository.ConcertRepository;
import com.concert.analytics.repository.ConcertSpecifications;
import com.concert.analytics.repository.SourceCountProjection;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ConcertService {

    /** 地区筛选项：样本太少的省份不列进下拉，避免选了看不出东西。 */
    private static final long REGION_OPTION_MIN_SAMPLE = 200L;
    private static final int REGION_OPTION_LIMIT = 40;

    private final ConcertRepository concertRepository;
    private final CommentRepository commentRepository;
    private final DatasetVersionService datasetVersionService;
    private final Clock clock;

    public ConcertService(
            ConcertRepository concertRepository,
            CommentRepository commentRepository,
            DatasetVersionService datasetVersionService,
            Clock clock) {
        this.concertRepository = concertRepository;
        this.commentRepository = commentRepository;
        this.datasetVersionService = datasetVersionService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ConcertItem> search(
            String query,
            String city,
            String artist,
            String category,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            int page,
            int size) {
        validateRange(startDate, endDate, minPrice, maxPrice);
        LocalDate today = LocalDate.now(clock);
        PageRequest pageRequest = PageRequest.of(
                page, size, Sort.by(Sort.Order.asc("showTime"), Sort.Order.asc("id")));
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        Page<Concert> result = latest == null
                ? Page.empty(pageRequest)
                : concertRepository.findAll(
                        ConcertSpecifications.filtered(
                                latest.getId(), query, city, artist, category, status, today,
                                startDate, endDate, minPrice, maxPrice),
                        pageRequest);
        return new PageResponse<>(
                result.getContent().stream()
                        .map(concert -> DtoMapper.concert(concert, 0, today))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.isFirst(), result.isLast());
    }

    @Transactional(readOnly = true)
    public ConcertDetailResponse detail(long id) {
        DataBatch latest = datasetVersionService.latestBatch()
                .orElseThrow(() -> new NotFoundException("Concert " + id + " was not found"));
        Concert concert = concertRepository.findByIdAndDataBatchIdAndCategory(
                        id, latest.getId(), ConcertPolicy.CATEGORY)
                .orElseThrow(() -> new NotFoundException("Concert " + id + " was not found"));
        PageResponse<CommentItem> emptyComments = new PageResponse<>(
                List.of(), 0, 0, 0, 0, true, true);
        return new ConcertDetailResponse(
                DtoMapper.concert(concert, 0, LocalDate.now(clock)),
                emptyComments,
                new PageResponse<>(List.of(), 0, 5, 0, 0, true, true),
                List.of(),
                "评论数据仅用于大麦平台、用户地区、主题和可验证艺人范围聚合，未建立可验证的具体演出场次关联。");
    }

    @Transactional(readOnly = true)
    public ConcertFiltersResponse filters() {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return new ConcertFiltersResponse(
                    "empty", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
        }
        LocalDate today = LocalDate.now(clock);
        ConcertRepository.PriceBoundsProjection prices = concertRepository
                .findPriceBoundsByDataBatchIdAndCategory(latest.getId(), ConcertPolicy.CATEGORY);
        List<String> statuses = new ArrayList<>(concertRepository
                .findUpcomingStatusesByDataBatchIdAndCategory(
                        latest.getId(), ConcertPolicy.CATEGORY, today.atStartOfDay()).stream()
                .map(ConcertPolicy::upcomingSaleStatus)
                .distinct()
                .toList());
        if (concertRepository.countEndedByDataBatchIdAndCategory(
                latest.getId(), ConcertPolicy.CATEGORY, today.atStartOfDay()) > 0) {
            statuses.add(0, ConcertPolicy.ENDED_STATUS);
        }
        List<String> cities = concertRepository
                .findCitiesByDataBatchIdAndCategory(latest.getId(), ConcertPolicy.CATEGORY).stream()
                .map(ConcertPolicy::normalizeCity)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> artists = concertRepository
                .findArtistsByDataBatchIdAndCategory(latest.getId(), ConcertPolicy.CATEGORY).stream()
                .map(ConcertPolicy::normalizeArtist)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> regions = commentRepository.findRegions(latest.getId(), REGION_OPTION_MIN_SAMPLE).stream()
                .map(SourceCountProjection::getLabel)
                .filter(Objects::nonNull)
                .limit(REGION_OPTION_LIMIT)
                .toList();
        List<String> platforms = commentRepository.countBySource(latest.getId()).stream()
                .map(SourceCountProjection::getLabel)
                .filter(Objects::nonNull)
                .toList();
        return new ConcertFiltersResponse(
                latest.getDatasetVersion(),
                cities,
                artists,
                regions,
                platforms,
                concertRepository.countByDataBatchIdAndCategory(latest.getId(), ConcertPolicy.CATEGORY) > 0
                        ? List.of(ConcertPolicy.CATEGORY) : List.of(),
                statuses,
                prices == null || prices.getMinPrice() == null ? BigDecimal.ZERO : prices.getMinPrice(),
                prices == null || prices.getMaxPrice() == null ? BigDecimal.ZERO : prices.getMaxPrice(),
                Instant.now());
    }

    @Cacheable(cacheNames = "concert-calendar", sync = true,
            key = "@datasetVersionService.currentVersion() + ':' + #year + ':' + #month + ':' + (#query ?: '') "
                    + "+ ':' + (#city ?: '') + ':' + (#artist ?: '') + ':' + (#category ?: '') "
                    + "+ ':' + (#status ?: '') + ':' + (#minPrice ?: '') + ':' + (#maxPrice ?: '')")
    @Transactional(readOnly = true)
    public ConcertCalendarResponse calendar(
            int year,
            int month,
            String query,
            String city,
            String artist,
            String category,
            String status,
            BigDecimal minPrice,
            BigDecimal maxPrice) {
        validateRange(null, null, minPrice, maxPrice);
        LocalDate today = LocalDate.now(clock);
        YearMonth requestedMonth = YearMonth.of(year, month);
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        if (latest == null) {
            return new ConcertCalendarResponse("empty", year, month, List.of(), Instant.now());
        }
        List<Concert> concerts = concertRepository.findAll(
                ConcertSpecifications.filtered(
                        latest.getId(), query, city, artist, category, status, today,
                        requestedMonth.atDay(1), requestedMonth.atEndOfMonth(), minPrice, maxPrice),
                Sort.by(Sort.Order.asc("showTime"), Sort.Order.asc("id")));
        return new ConcertCalendarResponse(
                latest.getDatasetVersion(),
                year,
                month,
                concerts.stream()
                        .map(concert -> DtoMapper.concert(concert, 0, today))
                        .toList(),
                Instant.now());
    }

    private void validateRange(LocalDate startDate, LocalDate endDate, BigDecimal minPrice, BigDecimal maxPrice) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must not exceed maxPrice");
        }
    }
}
