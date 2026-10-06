package com.engineeringlens.scenario.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The EC2 production stack (deploy/docker-compose.prod.yml) keeps its security and Scenario Lab guarantees: code runs
 * in the local Docker sandbox, only the HTTPS proxy is published, and only the backend can reach the Docker socket.
 */
class ProductionStackTest {

    private static final Path DEPLOY = Path.of("../deploy");

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws Exception {
        Map<String, Object> compose = new Yaml().load(Files.readString(DEPLOY.resolve("docker-compose.prod.yml")));
        return (Map<String, Map<String, Object>>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(Map<String, Object> service) {
        return (Map<String, Object>) service.get("environment");
    }

    @Test
    void scenarioLabRunsInTheLocalDockerSandboxWithTheProductionProfile() throws Exception {
        Map<String, Object> env = environment(services().get("backend"));
        assertThat(env.get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
        // Fixed values, not ${...}: the prod profile's own default is the Railway runner, which this stack doesn't have.
        assertThat(env.get("SCENARIO_EXECUTION_PROVIDER")).isEqualTo("docker");
        assertThat(env.get("SCENARIO_EXECUTION_ENABLED")).isEqualTo("true");
        assertThat(env).doesNotContainKeys("SCENARIO_RUNNER_URL", "SCENARIO_RUNNER_TOKEN", "DOCKER_HOST");
    }

    @Test
    void onlyTheHttpsProxyPublishesPorts() throws Exception {
        Map<String, Map<String, Object>> services = services();
        assertThat(services).containsOnlyKeys("postgres", "backend", "caddy");
        assertThat(services.get("postgres")).doesNotContainKeys("ports", "expose", "network_mode");
        assertThat(services.get("backend")).doesNotContainKeys("ports", "expose", "network_mode");
        assertThat(services.get("caddy").get("ports")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("${HTTP_PORT:-80}:80", "${HTTPS_PORT:-443}:443", "${HTTPS_PORT:-443}:443/udp");
    }

    @Test
    void onlyTheLockedDownBackendGetsTheDockerSocket() throws Exception {
        Map<String, Map<String, Object>> services = services();
        services.forEach((name, service) -> {
            String volumes = String.valueOf(service.get("volumes"));
            assertThat(volumes.contains("docker.sock")).as(name + " mounts the Docker socket").isEqualTo(name.equals("backend"));
            assertThat(service).as(name).doesNotContainKey("privileged");
        });
        Map<String, Object> backend = services.get("backend");
        assertThat(backend.get("volumes")).isEqualTo(List.of("/var/run/docker.sock:/var/run/docker.sock"));
        assertThat(backend.get("read_only")).isEqualTo(true);
        assertThat(backend.get("security_opt")).isEqualTo(List.of("no-new-privileges:true"));
        assertThat(backend.get("group_add")).isEqualTo(List.of("${DOCKER_GID:?set DOCKER_GID}"));
    }

    @Test
    void theProxyForwardsToTheBackend() throws Exception {
        assertThat(Files.readString(DEPLOY.resolve("Caddyfile"))).contains("{$API_HOST}").contains("reverse_proxy backend:8080");
    }

    @Test
    void backupsUseTheSamePostgresImageAsProduction() throws Exception {
        String image = String.valueOf(services().get("postgres").get("image"));
        assertThat(image).matches("postgres:17@sha256:[0-9a-f]{64}");
        assertThat(Files.readString(DEPLOY.resolve("backup.sh"))).contains(image);
    }

    @Test
    void everyVariableTheStackNeedsIsInTheEnvironmentTemplate() throws Exception {
        String compose = Files.readString(DEPLOY.resolve("docker-compose.prod.yml"));
        String template = Files.readString(DEPLOY.resolve(".env.production.example"));
        Set<String> used = new TreeSet<>();
        // $${X} is escaped: the container expands it from its own environment, not from deploy/.env.
        Matcher m = Pattern.compile("(?<!\\$)\\$\\{([A-Z_]+)").matcher(compose);
        while (m.find()) {
            used.add(m.group(1));
        }
        // Port overrides exist only for local rehearsals; on the server they stay 80/443.
        used.removeAll(Set.of("HTTP_PORT", "HTTPS_PORT"));
        assertThat(used).isNotEmpty().allSatisfy(v -> assertThat(template).as(v).containsPattern("(?m)^" + v + "="));
    }
}
