package com.engineeringlens.scenario;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.engineeringlens.scenario.execution.ExecutionProperties;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
class ScenarioConfig {
}
