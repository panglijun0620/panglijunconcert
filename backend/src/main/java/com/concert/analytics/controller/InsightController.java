package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.InsightResponse;
import com.concert.analytics.service.InsightService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风险对象解读接口。
 *
 * <p>永远返回 200：对象不存在、模型不可用都只是 text 为空或走模板，不向页面抛错。
 */
@Validated
@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping
    public InsightResponse insight(@RequestParam @NotBlank @Size(max = 120) String object) {
        return insightService.insight(object);
    }
}
