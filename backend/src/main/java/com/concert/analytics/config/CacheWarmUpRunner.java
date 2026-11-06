package com.concert.analytics.config;

import com.concert.analytics.service.AnalyticsService;
import com.concert.analytics.service.ConcertService;
import com.concert.analytics.service.RecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 启动后自动预热聚合缓存：后台线程依次调用各聚合接口，
 * 把结果写入 Redis，这样部署完成后第一次打开页面就是热缓存，
 * 不会出现“第一次 5 秒、之后 0.3 秒”的情况，也避免了并发下同时重建缓存的击穿。
 */
@Component
public class CacheWarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmUpRunner.class);

    private final AnalyticsService analyticsService;
    private final ConcertService concertService;
    private final RecommendationService recommendationService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cache-warmup");
        t.setDaemon(true);
        return t;
    });

    public CacheWarmUpRunner(AnalyticsService analyticsService, ConcertService concertService,
                             RecommendationService recommendationService) {
        this.analyticsService = analyticsService;
        this.concertService = concertService;
        this.recommendationService = recommendationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        executor.execute(() -> {
            // 等应用完全就绪、数据库连接池稳定后再开始算
            sleep(3000);
            warm("dashboard", analyticsService::dashboard);
            warm("market", analyticsService::market);
            warm("sentiment", () -> analyticsService.sentiment(null, null));
            warm("data-quality", analyticsService::dataQuality);
            warm("recommendations", () -> recommendationService.recommend(null, null, null, "match", 8));
            LocalDate now = LocalDate.now();
            warm("concert-calendar", () -> concertService.calendar(
                    now.getYear(), now.getMonthValue(),
                    null, null, null, null, null, null, null));
        });
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void warm(String name, Runnable task) {
        long start = System.currentTimeMillis();
        try {
            task.run();
            log.info("Cache warm-up [{}] done in {} ms", name, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("Cache warm-up [{}] failed: {}", name, e.getMessage());
        }
    }
}
