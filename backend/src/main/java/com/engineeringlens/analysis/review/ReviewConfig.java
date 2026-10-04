package com.engineeringlens.analysis.review;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class ReviewConfig {

    /**
     * Reviews take minutes, so they run off the request thread. A small fixed pool: each review is one
     * long model call, and provider quotas, not CPU, are the limit. app.review.async=false runs them
     * inline (used by tests).
     */
    @Bean
    Executor reviewExecutor(@Value("${app.review.async:true}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("review-");
        executor.initialize();
        return executor;
    }
}
