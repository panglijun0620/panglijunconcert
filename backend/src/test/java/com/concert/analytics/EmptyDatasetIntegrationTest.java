package com.concert.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:empty-dataset;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(statements = {
        "DELETE FROM sentiment_risk",
        "DELETE FROM data_quality_metric",
        "DELETE FROM analysis_metric",
        "DELETE FROM clean_comment",
        "DELETE FROM clean_concert",
        "DELETE FROM spark_job_run",
        "DELETE FROM analytics_batch"
})
class EmptyDatasetIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @Test
    void allReadModelsRemainStableWhenNoBatchExists() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("empty"))
                .andExpect(jsonPath("$.summary.concerts").value(0))
                .andExpect(jsonPath("$.risks", hasSize(0)));
        mockMvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/concerts/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("empty"))
                .andExpect(jsonPath("$.cities", hasSize(0)))
                .andExpect(jsonPath("$.artists", hasSize(0)))
                .andExpect(jsonPath("$.categories", hasSize(0)))
                .andExpect(jsonPath("$.statuses", hasSize(0)))
                .andExpect(jsonPath("$.minPrice").value(0))
                .andExpect(jsonPath("$.maxPrice").value(0));
        mockMvc.perform(get("/api/v1/concerts/calendar")
                        .param("year", "2025")
                        .param("month", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("empty"))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/v1/market"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregationScope").value("GLOBAL_CURRENT_BATCH"))
                .andExpect(jsonPath("$.cities", hasSize(0)))
                .andExpect(jsonPath("$.prices", hasSize(0)))
                .andExpect(jsonPath("$.weekdays", hasSize(0)));
        mockMvc.perform(get("/api/v1/sentiment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSamples").value(0))
                .andExpect(jsonPath("$.distribution", hasSize(5)));
        mockMvc.perform(get("/api/v1/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/v1/data/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.inputCount").value(0))
                .andExpect(jsonPath("$.sourceMetrics", hasSize(0)));
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}
