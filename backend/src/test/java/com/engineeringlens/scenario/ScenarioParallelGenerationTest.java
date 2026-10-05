package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Real background generation with real threads: builds run in parallel but never more than the configured
 * concurrency, and parallel saves still give every scenario its own position.
 */
@TestPropertySource(properties = { "app.scenario.async=true", "scenario.generation.build-concurrency=3" })
class ScenarioParallelGenerationTest extends ScenarioFlowSupport {

    @Autowired
    ScenarioLabRepository labs;

    @Autowired
    ScenarioRepository scenarios;

    @Test
    void buildsRunInParallelWithinTheBoundAndEveryScenarioGetsItsOwnPosition() throws Exception {
        String auth = register("parallel@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        answers = p -> {
            if (!ScenarioFixtures.isBuild(p)) {
                return defaultAnswer(p);
            }
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150); // a build takes a while, so overlap is observable
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return defaultAnswer(p);
        };

        UUID labId = UUID.fromString(startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10));
        for (int i = 0; i < 200 && labs.findById(labId).orElseThrow().getStatus() == ScenarioLabStatus.GENERATING; i++) {
            Thread.sleep(100);
        }

        assertThat(labs.findById(labId).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.ACTIVE);
        assertThat(scenarios.findByLabIdOrderByPositionAsc(labId)).extracting(Scenario::getPosition)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(peak.get()).isBetween(2, 3); // parallel, and never above the bound
    }
}
