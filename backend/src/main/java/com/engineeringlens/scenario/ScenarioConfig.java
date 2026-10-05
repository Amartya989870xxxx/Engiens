package com.engineeringlens.scenario;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.engineeringlens.scenario.execution.ExecutionProperties;
import com.engineeringlens.scenario.generation.GenerationProperties;

@Configuration
@EnableConfigurationProperties({ ExecutionProperties.class, GenerationProperties.class })
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

    /**
     * Builds scenarios in parallel, bounded globally: at most build-concurrency builds (each an AI call plus sandbox
     * runs) at once across all labs; further builds wait their turn. Inline in tests (app.scenario.async=false).
     */
    @Bean
    Executor scenarioBuildExecutor(@Value("${app.scenario.async:true}") boolean async, GenerationProperties generation) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, generation.buildConcurrency()));
        executor.setMaxPoolSize(Math.max(1, generation.buildConcurrency()));
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("scenario-build-");
        executor.initialize();
        return executor;
    }
}
