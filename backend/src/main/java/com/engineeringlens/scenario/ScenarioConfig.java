package com.engineeringlens.scenario;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.engineeringlens.scenario.execution.ExecutionProperties;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
class ScenarioConfig {

    /**
     * Scenario generation and evaluation take minutes (several model calls plus sandbox runs), so they run
     * off the request thread, like reviews. app.scenario.async=false runs them inline (used by tests).
     */
    @Bean
    Executor scenarioExecutor(@Value("${app.scenario.async:true}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("scenario-");
        executor.initialize();
        return executor;
    }
}
