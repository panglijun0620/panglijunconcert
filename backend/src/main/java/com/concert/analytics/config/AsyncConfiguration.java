package com.concert.analytics.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行配置。
 *
 * <p>操作日志在请求结束后单独用一个线程写库。原因是导出接口本身跑在只读事务里，
 * 请求线程手上那条数据库连接被标记成只读，直接在原线程写会报
 * “Connection is read-only”。换到独立线程就拿到一条可写的连接，问题从根上避开。
 */
@Configuration
@EnableAsync
public class AsyncConfiguration implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfiguration.class);

    @Bean(name = "operationLogExecutor")
    public Executor operationLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("oplog-");
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return operationLogExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.warn("异步任务失败（{}）：{}", method.getName(), throwable.getMessage());
    }
}
