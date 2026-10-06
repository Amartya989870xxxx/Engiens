package com.engineeringlens.scenario.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Configuration decides where code runs: Docker locally (the default), the runner service in production. */
class ExecutionProviderSelectionTest {

    @Configuration
    @EnableConfigurationProperties(ExecutionProperties.class)
    @Import({ DockerSandboxExecutionProvider.class, RemoteRunnerExecutionProvider.class })
    static class Providers {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Providers.class);

    @Test
    void withoutConfigurationTheLocalDockerSandboxIsUsed() {
        context.run(c -> {
            assertThat(c).hasSingleBean(ExecutionProvider.class);
            assertThat(c.getBean(ExecutionProvider.class)).isInstanceOf(DockerSandboxExecutionProvider.class);
        });
        context.withPropertyValues("scenario.execution.provider=docker")
                .run(c -> assertThat(c.getBean(ExecutionProvider.class)).isInstanceOf(DockerSandboxExecutionProvider.class));
    }

    @Test
    void providerRunnerSelectsTheRunnerService() {
        context.withPropertyValues("scenario.execution.provider=runner", "scenario.execution.runner-url=http://runner.railway.internal:8090",
                "scenario.execution.runner-token=0123456789abcdef").run(c -> {
                    assertThat(c).hasSingleBean(ExecutionProvider.class);
                    assertThat(c.getBean(ExecutionProvider.class)).isInstanceOf(RemoteRunnerExecutionProvider.class);
                });
    }

    @Test
    void theProductionProfileDefaultsToTheRunnerAndLocalConfigurationToDocker() throws Exception {
        assertThat(load("application-prod.properties").getProperty("scenario.execution.provider"))
                .isEqualTo("${SCENARIO_EXECUTION_PROVIDER:runner}");
        assertThat(load("application.properties").getProperty("scenario.execution.provider"))
                .isEqualTo("${SCENARIO_EXECUTION_PROVIDER:docker}");
        context.withPropertyValues("scenario.execution.provider=runner")
                .run(c -> assertThat(c.getBean(ExecutionProvider.class)).isInstanceOf(RemoteRunnerExecutionProvider.class));
    }

    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = ExecutionProviderSelectionTest.class.getResourceAsStream("/" + name)) {
            p.load(in);
        }
        return p;
    }
}
