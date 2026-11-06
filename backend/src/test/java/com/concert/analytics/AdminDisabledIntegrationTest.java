package com.concert.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "concert.security.admin.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void doesNotRegisterAdministrationEndpointWhenDisabled() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/cache")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
    }
}
