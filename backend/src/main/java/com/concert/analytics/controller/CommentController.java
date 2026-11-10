package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.CommentSearchResponse;
import com.concert.analytics.service.CommentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原始评论下钻接口。
 *
 * <p>入参全部可选，可以组合使用：点风险对象时传 artist，点主题图时传 topic，点地区图时传 region。
 */
@Validated
@RestController
@RequestMapping("/api/v1/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public CommentSearchResponse comments(
            @RequestParam(required = false) @Size(max = 120) String artist,
            @RequestParam(required = false) @Size(max = 100) String topic,
            @RequestParam(required = false) @Size(max = 80) String region,
            @RequestParam(required = false) @Size(max = 16) String sentiment,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return commentService.search(artist, topic, region, sentiment, page, size);
    }
}
