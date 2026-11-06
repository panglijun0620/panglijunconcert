package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.DashboardResponse;
import com.concert.analytics.api.ApiDtos.DataQualityResponse;
import com.concert.analytics.api.ApiDtos.JobItem;
import com.concert.analytics.api.ApiDtos.MarketResponse;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.api.ApiDtos.RecommendationResponse;
import com.concert.analytics.api.ApiDtos.SentimentResponse;
import com.concert.analytics.service.AnalyticsService;
import com.concert.analytics.service.JobService;
import com.concert.analytics.service.RecommendationService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Validated
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final RecommendationService recommendationService;
    private final JobService jobService;

    public AnalyticsController(
            AnalyticsService analyticsService,
            RecommendationService recommendationService,
            JobService jobService) {
        this.analyticsService = analyticsService;
        this.recommendationService = recommendationService;
        this.jobService = jobService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return analyticsService.dashboard();
    }

    @GetMapping("/market")
    public MarketResponse market() {
        return analyticsService.market();
    }

    @GetMapping("/sentiment")
    public SentimentResponse sentiment(
            @RequestParam(required = false) @Size(max = 40) String region,
            @RequestParam(required = false) @Size(max = 40) String platform) {
        return analyticsService.sentiment(region, platform);
    }

    @GetMapping("/recommendations")
    public RecommendationResponse recommendations(
            @RequestParam(required = false) @Size(max = 40) String city,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("1000000") BigDecimal maxBudget,
            @RequestParam(required = false) @Size(max = 80) String artist,
            @RequestParam(defaultValue = "match") @Pattern(regexp = "match|price") String sort,
            @RequestParam(defaultValue = "8") @Min(1) @Max(20) int limit) {
        return recommendationService.recommend(city, maxBudget, artist, sort, limit);
    }

    @GetMapping("/data/quality")
    public DataQualityResponse dataQuality() {
        return analyticsService.dataQuality();
    }

    @GetMapping("/jobs")
    public PageResponse<JobItem> jobs(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return jobService.jobs(page, size);
    }
}
