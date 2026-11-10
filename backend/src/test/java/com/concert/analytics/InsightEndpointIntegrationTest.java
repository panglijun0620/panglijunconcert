package com.concert.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 解读接口的对外约定：永远 200；没有该对象时返回 source=none 而不是报错。
 * 测试环境没有配置密钥，因此生成结果必须落在模板上。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InsightEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsTemplateInsightForKnownRiskObject() throws Exception {
        mockMvc.perform(get("/api/v1/insights").param("object", "陈奕迅"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectName").value("陈奕迅"))
                .andExpect(jsonPath("$.source").value("template"))
                .andExpect(jsonPath("$.text", containsString("陈奕迅")))
                .andExpect(jsonPath("$.sampleSize", greaterThan(0)));
    }

    @Test
    void returnsEmptyInsightInsteadOfErrorForUnknownObject() throws Exception {
        mockMvc.perform(get("/api/v1/insights").param("object", "不存在的艺人"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("none"))
                .andExpect(jsonPath("$.text").value(""));
    }
}
