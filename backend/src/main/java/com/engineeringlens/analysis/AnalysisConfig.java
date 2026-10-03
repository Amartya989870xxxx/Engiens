package com.engineeringlens.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.engineeringlens.analysis.context.ContextProperties;

@Configuration
@EnableConfigurationProperties(ContextProperties.class)
class AnalysisConfig {
}
