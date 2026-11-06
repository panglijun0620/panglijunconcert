package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.HealthResponse;
import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.repository.CommentRepository;
import com.concert.analytics.repository.ConcertRepository;
import com.concert.analytics.service.DatasetVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ConcertRepository concertRepository;
    private final CommentRepository commentRepository;
    private final DatasetVersionService datasetVersionService;

    public HealthController(
            ConcertRepository concertRepository,
            CommentRepository commentRepository,
            DatasetVersionService datasetVersionService) {
        this.concertRepository = concertRepository;
        this.commentRepository = commentRepository;
        this.datasetVersionService = datasetVersionService;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        DataBatch latest = datasetVersionService.latestBatch().orElse(null);
        return new HealthResponse(
                "UP", "concert-analytics-backend", "connected",
                latest == null ? 0 : concertRepository.countByDataBatchId(latest.getId()),
                latest == null ? 0 : commentRepository.countByDataBatchId(latest.getId()),
                Instant.now());
    }
}
