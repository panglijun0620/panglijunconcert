package com.concert.analytics.config;

import com.concert.analytics.web.OperationLogInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final OperationLogInterceptor operationLogInterceptor;

    public WebConfiguration(OperationLogInterceptor operationLogInterceptor) {
        this.operationLogInterceptor = operationLogInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/v1/export/**", "/api/v1/admin/**");
    }
}
