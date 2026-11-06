package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.ConcertDetailResponse;
import com.concert.analytics.api.ApiDtos.ConcertCalendarResponse;
import com.concert.analytics.api.ApiDtos.ConcertFiltersResponse;
import com.concert.analytics.api.ApiDtos.ConcertItem;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.service.ConcertService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/api/v1/concerts")
public class ConcertController {

    private final ConcertService concertService;

    public ConcertController(ConcertService concertService) {
        this.concertService = concertService;
    }

    @GetMapping
    public PageResponse<ConcertItem> concerts(
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String artist,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return concertService.search(
                query, city, artist, category, status, startDate, endDate, minPrice, maxPrice, page, size);
    }

    @GetMapping("/filters")
    public ConcertFiltersResponse filters() {
        return concertService.filters();
    }

    @GetMapping("/calendar")
    public ConcertCalendarResponse calendar(
            @RequestParam @Min(2000) @Max(2100) int year,
            @RequestParam @Min(1) @Max(12) int month,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String artist,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice) {
        return concertService.calendar(
                year, month, query, city, artist, category, status, minPrice, maxPrice);
    }

    @GetMapping("/{id}")
    public ConcertDetailResponse detail(@PathVariable @Positive long id) {
        return concertService.detail(id);
    }
}
