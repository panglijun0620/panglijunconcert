package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.AnnotationSample;
import com.concert.analytics.service.EvaluationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 情感规则评估用的抽样接口。
 *
 * <p>只读，不写库。抽出来的样本供人工标注页面逐条判定，再用标注结果计算规则的准确率与 F1。
 */
@Validated
@RestController
@RequestMapping("/api/v1/eval")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/samples")
    public Map<String, Object> samples(
            @RequestParam(defaultValue = "40") @Min(5) @Max(120) int perClass) {
        List<AnnotationSample> samples = evaluationService.samples(perClass);
        return Map.of(
                "perClass", perClass,
                "total", samples.size(),
                "samples", samples);
    }
}
