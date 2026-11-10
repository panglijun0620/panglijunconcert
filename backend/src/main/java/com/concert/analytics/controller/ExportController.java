package com.concert.analytics.controller;

import com.concert.analytics.export.CsvFile;
import com.concert.analytics.service.ExportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 数据导出接口。
 *
 * <p>三个出口分别对应页面上的三处导出按钮：市场榜单、预警名单、评论检索结果。
 * 统一输出 CSV，带 UTF-8 BOM，Excel 双击即可正常显示中文。
 */
@Validated
@RestController
@RequestMapping("/api/v1/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/market")
    public ResponseEntity<byte[]> market(
            @RequestParam(defaultValue = "artists")
            @Size(max = 20) String dimension) {
        return download(exportService.market(dimension));
    }

    @GetMapping("/risks")
    public ResponseEntity<byte[]> risks() {
        return download(exportService.risks());
    }

    @GetMapping("/comments")
    public ResponseEntity<byte[]> comments(
            @RequestParam(required = false) @Size(max = 120) String artist,
            @RequestParam(required = false) @Size(max = 100) String topic,
            @RequestParam(required = false) @Size(max = 80) String region,
            @RequestParam(required = false) @Size(max = 16) String sentiment,
            @RequestParam(defaultValue = "5000") @Min(1) @Max(20000) int limit) {
        return download(exportService.comments(artist, topic, region, sentiment, limit));
    }

    private static ResponseEntity<byte[]> download(CsvFile file) {
        String encoded = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.csv\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file.content());
    }
}
