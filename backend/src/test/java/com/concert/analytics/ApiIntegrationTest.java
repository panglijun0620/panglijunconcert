package com.concert.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiIntegrationTest.FixedClockConfiguration.class)
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void exposesHealthAndStableDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.concerts", greaterThan(0)))
                .andExpect(jsonPath("$.comments", greaterThan(0)));

        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("sample-v1"))
                .andExpect(jsonPath("$.summary.concerts").value(4))
                .andExpect(jsonPath("$.summary.activeRisks").value(2))
                .andExpect(jsonPath("$.risks[0].riskLevel").value("ALERT"))
                .andExpect(jsonPath("$.risks[1].riskLevel").value("WATCH"))
                .andExpect(jsonPath("$.risks[0].triggerRule").isNotEmpty());
    }

    @Test
    void pagesAndFiltersConcertsAndReturnsDetails() throws Exception {
        mockMvc.perform(get("/api/v1/concerts")
                        .param("city", "北京")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].concertName").value("嘉年华 - 北京场"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/concerts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concert.artistName").value("周杰伦"))
                .andExpect(jsonPath("$.comments.totalElements").value(0))
                .andExpect(jsonPath("$.artistComments.totalElements").value(0))
                .andExpect(jsonPath("$.commentScopeNotice").isNotEmpty());

        mockMvc.perform(get("/api/v1/concerts/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments.totalElements").value(0))
                .andExpect(jsonPath("$.artistComments.totalElements").value(0));
    }

    @Test
    @Transactional
    @Sql(statements = """
            INSERT INTO clean_concert
                (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
                 min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
            VALUES
                (12010, 1, '历史歌手', '演唱会', '历史待售演唱会', '历史城', '历史场馆',
                 '2025-04-17 19:30:00', '580', 580, 580, '即将开售', 'TEST', 'historical-sale-status',
                 'https://example.invalid/historical-sale-status', '2025-04-18 10:00:00', '2025-04-18 10:13:00');
            """)
    void completedShowsUseEndedStatusAcrossSearchDetailCalendarAndFilters() throws Exception {
        mockMvc.perform(get("/api/v1/concerts").param("q", "历史待售演唱会"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].saleStatus").value("演出已结束"));

        mockMvc.perform(get("/api/v1/concerts").param("status", "演出已结束"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(12010));

        mockMvc.perform(get("/api/v1/concerts").param("status", "即将开售"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/concerts/12010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concert.saleStatus").value("演出已结束"));

        mockMvc.perform(get("/api/v1/concerts/calendar").param("year", "2025").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].saleStatus").value("演出已结束"));

        mockMvc.perform(get("/api/v1/concerts/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses", hasItem("演出已结束")))
                .andExpect(jsonPath("$.statuses", not(hasItem("即将开售"))));
    }

    @Test
    @Transactional
    @Sql(statements = """
            INSERT INTO clean_concert
                (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
                 min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
            VALUES
                (12012, 1, '票务艺人', '演唱会', '待核实票务场次', '票务城', '票务场馆',
                 '2025-12-01 19:30:00', '580', 580, 580, '去购票', 'TEST', 'purchase-action',
                 'https://example.invalid/purchase-action', '2025-04-18 10:00:00', '2025-04-18 10:13:00');
            """)
    void purchaseActionIsNotPresentedOrScoredAsLiveSaleStatus() throws Exception {
        mockMvc.perform(get("/api/v1/concerts").param("q", "待核实票务场次"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].saleStatus").value("票务状态待核实"));

        mockMvc.perform(get("/api/v1/concerts").param("status", "票务状态待核实"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/concerts/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses", hasItem("票务状态待核实")))
                .andExpect(jsonPath("$.statuses", not(hasItem("去购票"))));

        mockMvc.perform(get("/api/v1/recommendations").param("artist", "票务艺人"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].concert.saleStatus").value("票务状态待核实"))
                .andExpect(jsonPath("$.items[0].score").value(33))
                .andExpect(jsonPath("$.items[0].reasons", not(hasItem("当前状态：去购票"))));
    }

    @Test
    @Transactional
    @Sql(statements = """
            INSERT INTO clean_concert
                (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
                 min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
            VALUES
                (12011, 1, '体育艺人', '音乐会', '体育比赛门票', '测试体育城', '体育馆',
                 '2025-08-17 19:30:00', '1', 1, 1, '售票中', 'TEST', 'unrelated-event',
                 'https://example.invalid/unrelated-event', '2025-04-18 10:00:00', '2025-04-18 10:13:00');
            """)
    void nonConcertCategoryDoesNotLeakIntoConcertFeatures() throws Exception {
        mockMvc.perform(get("/api/v1/concerts").param("q", "体育比赛门票"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/concerts").param("category", "音乐会"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/concerts/12011"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/concerts/calendar").param("year", "2025").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(get("/api/v1/concerts/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities", not(hasItem("测试体育城"))))
                .andExpect(jsonPath("$.artists", not(hasItem("体育艺人"))))
                .andExpect(jsonPath("$.categories", contains("演唱会")))
                .andExpect(jsonPath("$.minPrice").value(355));

        mockMvc.perform(get("/api/v1/recommendations").param("city", "测试体育城"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void returnsCurrentBatchFilterMetadataAndStableCalendarResults() throws Exception {
        YearMonth firstConcertMonth = showMonth(1);

        mockMvc.perform(get("/api/v1/concerts/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("sample-v1"))
                .andExpect(jsonPath("$.cities", hasSize(4)))
                .andExpect(jsonPath("$.cities", containsInAnyOrder("上海", "北京", "广州", "成都")))
                .andExpect(jsonPath("$.artists", hasSize(3)))
                .andExpect(jsonPath("$.artists", containsInAnyOrder("周杰伦", "陈奕迅", "五月天")))
                .andExpect(jsonPath("$.categories", contains("演唱会")))
                .andExpect(jsonPath("$.statuses", containsInAnyOrder("已售罄", "售票中", "预售")))
                .andExpect(jsonPath("$.minPrice").value(355))
                .andExpect(jsonPath("$.maxPrice").value(1880));

        mockMvc.perform(get("/api/v1/concerts/calendar")
                        .param("year", Integer.toString(firstConcertMonth.getYear()))
                        .param("month", Integer.toString(firstConcertMonth.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("sample-v1"))
                .andExpect(jsonPath("$.year").value(firstConcertMonth.getYear()))
                .andExpect(jsonPath("$.month").value(firstConcertMonth.getMonthValue()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(1));

        mockMvc.perform(get("/api/v1/concerts/calendar")
                        .param("year", "2025")
                        .param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsUnifiedErrorsForInvalidQueriesAndMissingConcerts() throws Exception {
        mockMvc.perform(get("/api/v1/concerts").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/concerts"));

        mockMvc.perform(get("/api/v1/concerts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void servesMarketSentimentQualityAndJobsFromMaterializedTables() throws Exception {
        mockMvc.perform(get("/api/v1/market"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregationScope").value("GLOBAL_CURRENT_BATCH"))
                .andExpect(jsonPath("$.cities", hasSize(4)))
                .andExpect(jsonPath("$.artists[0].unit").value("score"));

        mockMvc.perform(get("/api/v1/sentiment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreScale").value("[-1,1]"))
                .andExpect(jsonPath("$.totalComments").value(5))
                .andExpect(jsonPath("$.unscoredSamples").value(0))
                .andExpect(jsonPath("$.negativeThreshold").value(-0.2))
                .andExpect(jsonPath("$.positiveThreshold").value(0.2))
                .andExpect(jsonPath("$.distribution", hasSize(5)))
                .andExpect(jsonPath("$.platforms[0].count", greaterThan(0)))
                .andExpect(jsonPath("$.risks[0].riskLevel").value("ALERT"))
                .andExpect(jsonPath("$.risks[1].riskLevel").value("WATCH"))
                .andExpect(jsonPath("$.risks[0].threshold").value(-0.2));

        mockMvc.perform(get("/api/v1/data/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.inputCount").value(12))
                .andExpect(jsonPath("$.sourceMetrics", hasSize(4)));

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.executionEngine=='SPARK_ON_YARN')]", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.items[?(@.jobType=='CRAWL_DAMAI')]", hasSize(greaterThan(0))))
                // 采集任务的数字口径要随接口返回，页面靠它在任务名下面解释「入库 / 采集」两列怎么读
                .andExpect(jsonPath("$.items[?(@.jobType=='CRAWL_SHOWSTART')].workerSummary",
                        contains("列表去重 60 条 → 详情抓取入库 104 场")))
                // 内部字段仍然不对外
                .andExpect(jsonPath("$.items[0].applicationId").doesNotExist())
                .andExpect(jsonPath("$.items[0].failureReason").doesNotExist());
    }

    @Test
    void sentimentRisksFollowTheRegionFilter() throws Exception {
        // 不筛选：风险来自整批的预计算结果，陈奕迅 ALERT + 关注艺人 WATCH 共两条
        mockMvc.perform(get("/api/v1/sentiment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(5))
                .andExpect(jsonPath("$.risks", hasSize(2)))
                .andExpect(jsonPath("$.risks[0].riskLevel").value("ALERT"));

        // 只筛广东：该地区在种子数据里只有 2 条评论，不足最小样本量 20，
        // 风险列表必须清空，不能继续显示整批算出来的那两条。
        mockMvc.perform(get("/api/v1/sentiment").param("region", "广东"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(2))
                .andExpect(jsonPath("$.risks", hasSize(0)));

        // 换一个地区，情绪汇总也要跟着变，不能和整批数字一样
        mockMvc.perform(get("/api/v1/sentiment").param("region", "江苏"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(1))
                .andExpect(jsonPath("$.risks", hasSize(0)));
    }

    @Test
    void exposesSeededGlobalPriceAndWeekdayMarketMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/market"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregationScope").value("GLOBAL_CURRENT_BATCH"))
                .andExpect(jsonPath("$.prices", hasSize(2)))
                .andExpect(jsonPath("$.prices[*].key", containsInAnyOrder("600_999", "1000_plus")))
                .andExpect(jsonPath("$.prices[?(@.key == '1000_plus')].value", contains(3.0)))
                .andExpect(jsonPath("$.weekdays", hasSize(4)))
                .andExpect(jsonPath("$.weekdays[0].label").isNotEmpty());
    }

    @Test
    void filtersSentimentAggregatesByRegionAndPlatform() throws Exception {
        mockMvc.perform(get("/api/v1/sentiment").param("region", "上海"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(1))
                .andExpect(jsonPath("$.averageScore").value(0.82));

        mockMvc.perform(get("/api/v1/sentiment").param("platform", "damai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(5))
                .andExpect(jsonPath("$.platforms", hasSize(1)))
                .andExpect(jsonPath("$.platforms[0].label").value("DAMAI"));

        mockMvc.perform(get("/api/v1/sentiment")
                        .param("region", "四川")
                        .param("platform", "DAMAI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(1))
                .andExpect(jsonPath("$.averageScore").value(-0.05));
    }

    @Test
    void recommendationsAlwaysExplainTheirRuleScore() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations")
                        .param("city", "北京")
                        .param("maxBudget", "1000")
                        .param("artist", "周杰伦"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("rule_based_rank; score is not a probability"))
                .andExpect(jsonPath("$.items[0].reasons[0]").isNotEmpty())
                .andExpect(jsonPath("$.items[0].score", greaterThan(0)));
    }

    @Test
    void sortsRecommendationsAndRejectsUnsupportedSortValues() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criteria.sort").value("match"))
                .andExpect(jsonPath("$.items[0].concert.id").value(2));

        mockMvc.perform(get("/api/v1/recommendations").param("sort", "price"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criteria.sort").value("price"))
                .andExpect(jsonPath("$.items[0].concert.id").value(4));

        mockMvc.perform(get("/api/v1/recommendations").param("sort", "heat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @Transactional
    @Sql(statements = {
            """
            INSERT INTO clean_concert
                (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
                 min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
            SELECT
                10000 + sequence_number, 1, '填充艺人', '演唱会', CONCAT('填充场次-', sequence_number),
                '填充城', '填充场馆',
                '2025-04-19 19:30:00', '999', 999, 999, '售票中', 'TEST',
                CONCAT('filler-', sequence_number),
                CONCAT('https://example.invalid/filler/', sequence_number),
                '2025-04-18 10:00:00', '2025-04-18 10:13:00'
            FROM SYSTEM_RANGE(1, 201) AS generated(sequence_number);
            """,
            """
            INSERT INTO clean_concert
                (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
                 min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
            VALUES
                (12000, 1, '目标艺人', '演唱会', '历史目标场次', '目标城', '目标场馆',
                 '2025-04-17 19:30:00', '300', 300, 300, '售票中', 'TEST', 'historical-target',
                 'https://example.invalid/historical-target', '2025-04-18 10:00:00', '2025-04-18 10:13:00'),
                (12001, 1, '目标艺人', '演唱会', '未来目标场次', '目标城', '目标场馆',
                 '2025-12-31 19:30:00', '420', 420, 420, '售票中', 'TEST', 'future-target',
                 'https://example.invalid/future-target', '2025-04-18 10:00:00', '2025-04-18 10:13:00'),
                (12002, 1, '目标艺人', '演唱会', '超预算目标场次', '目标城', '目标场馆',
                 '2025-12-30 19:30:00', '900', 900, 900, '售票中', 'TEST', 'over-budget-target',
                 'https://example.invalid/over-budget-target', '2025-04-18 10:00:00', '2025-04-18 10:13:00');
            """
    })
    void recommendsDatabaseFilteredFutureCandidateBeyondFormerTwoHundredRowCap() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations")
                        .param("city", "目标城")
                        .param("artist", "目标艺人")
                        .param("maxBudget", "500")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].concert.id").value(12001))
                .andExpect(jsonPath("$.items[0].concert.concertName").value("未来目标场次"))
                .andExpect(jsonPath("$.items[0].reasons", hasItem("匹配城市：目标城")))
                .andExpect(jsonPath("$.items[0].reasons", hasItem("匹配艺人偏好：目标艺人")));
    }

    @Test
    void protectsCacheAdministrationWithSingleConfiguredAdministrator() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/cache"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(delete("/api/v1/admin/cache")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").isArray())
                .andExpect(jsonPath("$.failed").isArray());
    }

    @Test
    @Transactional
    @Sql(statements = {
            """
            INSERT INTO data_quality_metric
                (data_batch_id, source_type, metric_key, metric_value, record_count,
                 missing_count, duplicate_count, generated_at)
            VALUES
                (1, 'DAMAI', 'SOURCE_COVERAGE', 5, 5, 0, 0, '2025-04-18 10:12:30'),
                (1, 'SHOWSTART', 'SOURCE_COVERAGE', 1, 1, 0, 0, '2025-04-18 10:12:30'),
                (1, 'ALL', 'CONCERT_ACCEPTED_RECORDS', 4, 4, 0, 0, '2025-04-18 10:12:30');
            """
    })
    void buildsCoverageOnlyFromExplicitSourceMetricsAndKeepsAllQualityRows() throws Exception {
        mockMvc.perform(get("/api/v1/data/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCoverage", hasSize(2)))
                .andExpect(jsonPath("$.sourceCoverage[*].sourceType", hasItem("DAMAI")))
                .andExpect(jsonPath("$.sourceCoverage[*].sourceType", hasItem("SHOWSTART")))
                .andExpect(jsonPath("$.sourceCoverage[*].sourceType", not(hasItem("ALL"))))
                .andExpect(jsonPath(
                        "$.sourceCoverage[?(@.sourceType == 'DAMAI')].recordCount", contains(5)))
                .andExpect(jsonPath("$.sourceMetrics", hasSize(7)))
                .andExpect(jsonPath("$.sourceMetrics[*].metricKey", hasItem("CONCERT_ACCEPTED_RECORDS")));
    }

    @Test
    @Transactional
    @Sql(statements = {
            "DELETE FROM data_quality_metric WHERE data_batch_id = 1",
            """
            INSERT INTO analytics_batch
                (id, batch_id, dataset_version, source_type, raw_path, status, input_count, success_count,
                 failed_count, missing_count, duplicate_count, collected_at, started_at, finished_at,
                 published_at, notes)
            VALUES
                (2, 'legacy-2025-03-01', 'sample-v0', 'MULTI_SOURCE', '/concert/raw/legacy-2025-03-01',
                 'PUBLISHED', 2, 2, 0, 0, 0, '2025-03-01 10:00:00', '2025-03-01 10:01:00',
                 '2025-03-01 10:02:00', '2025-03-01 10:03:00', 'Historical full snapshot');
            """,
            """
            INSERT INTO clean_concert
                (id, data_batch_id, artist_name, category, concert_name, city, venue, show_time, price_text,
                 min_price, max_price, sale_status, source_type, source_record_id, source_url, collected_at, created_at)
            VALUES
                (1001, 2, '周杰伦', '演唱会', '嘉年华 - 上海场', '上海', '梅赛德斯-奔驰文化中心',
                 '2025-05-18 19:30:00', '580 / 880 / 1280 / 1680', 580, 1680, '已售罄', 'DAMAI',
                 'legacy-concert-1', 'https://example.invalid/legacy/concert/1',
                 '2025-03-01 10:00:00', '2025-03-01 10:03:00'),
                (1002, 2, '历史艺人', '音乐节', '历史批次独有场次', '历史城', '历史场馆',
                 '2025-05-20 19:30:00', '99 / 9999', 99, 9999, '历史状态', 'TEST',
                 'legacy-concert-2', 'https://example.invalid/legacy/concert/2',
                 '2025-03-01 10:00:00', '2025-03-01 10:03:00');
            """,
            """
            INSERT INTO clean_comment
                (id, concert_id, data_batch_id, artist_name, comment_text, comment_time, like_count, user_region,
                 source_type, source_record_id, source_url, collected_at, sentiment_score, primary_topic)
            VALUES
                (1001, 1001, 2, '周杰伦', '旧批次重复评论。', '2025-02-20 12:00:00', 5, '上海',
                 'DAMAI', 'legacy-comment-1', 'https://example.invalid/legacy/comment/1',
                 '2025-03-01 10:00:00', -0.90, '历史反馈');
            """,
            """
            INSERT INTO sentiment_risk
                (id, data_batch_id, scope_type, scope_id, object_name, negative_ratio, primary_negative_topic,
                 sample_size, threshold_value, minimum_sample_size, trigger_rule, detected_at)
            VALUES
                (1001, 2, 'ARTIST', 'legacy-artist', '历史艺人', 0.50, '历史反馈', 40, -0.20, 20,
                 'sentiment_score <= -0.20 的评论占比 >= 0.40 且样本量 >= 20', '2025-03-01 10:02:00');
            """
    })
    void isolatesCurrentReadModelsAndUsesOnlyCurrentBatchRisks() throws Exception {
        YearMonth firstConcertMonth = showMonth(1);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concerts").value(4))
                .andExpect(jsonPath("$.comments").value(5));

        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("sample-v1"))
                .andExpect(jsonPath("$.summary.concerts").value(4))
                .andExpect(jsonPath("$.summary.comments").value(5))
                .andExpect(jsonPath("$.summary.activeRisks").value(2));

        mockMvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.totalElements").value(4));

        mockMvc.perform(get("/api/v1/concerts/1001"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/concerts/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities", not(hasItem("历史城"))))
                .andExpect(jsonPath("$.artists", not(hasItem("历史艺人"))))
                .andExpect(jsonPath("$.categories", not(hasItem("音乐节"))))
                .andExpect(jsonPath("$.maxPrice").value(1880));

        mockMvc.perform(get("/api/v1/concerts/calendar")
                        .param("year", Integer.toString(firstConcertMonth.getYear()))
                        .param("month", Integer.toString(firstConcertMonth.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(1));

        mockMvc.perform(get("/api/v1/sentiment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(5))
                .andExpect(jsonPath("$.risks", hasSize(2)))
                .andExpect(jsonPath("$.risks[*].riskLevel", hasItem("ALERT")))
                .andExpect(jsonPath("$.risks[*].riskLevel", hasItem("WATCH")))
                .andExpect(jsonPath("$.risks[*].batchId", hasItem("sample-2025-04-18")))
                .andExpect(jsonPath("$.risks[*].batchId", not(hasItem("legacy-2025-03-01"))));

        mockMvc.perform(get("/api/v1/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("sample-v1"))
                .andExpect(jsonPath("$.items", hasSize(4)));

        mockMvc.perform(get("/api/v1/data/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCoverage", hasSize(3)))
                .andExpect(jsonPath(
                        "$.sourceCoverage[?(@.sourceType == 'DAMAI')].recordCount", contains(7)));
    }

    @Test
    @Transactional
    void databaseRejectsSentimentOutsideMinusOneToOne() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO clean_comment
                    (concert_id, data_batch_id, artist_name, comment_text, like_count, source_type,
                     source_url, collected_at, sentiment_score)
                VALUES (1, 1, 'test', 'invalid score', 0, 'TEST', 'https://example.invalid', CURRENT_TIMESTAMP, 1.01)
                """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    @Sql(statements = """
            INSERT INTO clean_comment
                (data_batch_id, comment_text, like_count, user_region, source_type,
                 source_record_id, source_url, collected_at, sentiment_score, primary_topic)
            VALUES
                (1, 'unscored comment', 0, 'Beijing', 'DAMAI', 'unscored-test',
                 'https://example.invalid/unscored-test', '2025-04-01 12:00:00', NULL, 'ticket');
            """)
    void excludesUnscoredCommentsFromSentimentGroupSampleSizes() throws Exception {
        mockMvc.perform(get("/api/v1/sentiment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalComments").value(6))
                .andExpect(jsonPath("$.totalSamples").value(5))
                .andExpect(jsonPath("$.unscoredSamples").value(1))
                .andExpect(jsonPath("$.platforms[0].count").value(5));
    }

    @Test
    void rejectsOversizedRecommendationFilters() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations").param("city", "x".repeat(41)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/recommendations").param("artist", "x".repeat(81)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/recommendations").param("maxBudget", "1000001"))
                .andExpect(status().isBadRequest());
    }

    private YearMonth showMonth(long concertId) {
        return jdbcTemplate.queryForObject(
                "SELECT show_time FROM clean_concert WHERE id = ?",
                (resultSet, rowNumber) -> YearMonth.from(resultSet.getTimestamp(1).toLocalDateTime()),
                concertId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2025-04-18T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
