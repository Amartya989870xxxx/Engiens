package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A lab never spends more than its build-call budget: here 0.5 calls per scenario, so 3 calls for 5 scenarios. */
@TestPropertySource(properties = "scenario.generation.build-calls-per-scenario=0.5")
class ScenarioCallBudgetTest extends ScenarioFlowSupport {

    @Test
    void generationStopsAtTheBuildCallBudgetAndKeepsWhatItHas() throws Exception {
        String auth = register("budget@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 5);

        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scenarios.length()").value(3))
                .andExpect(jsonPath("$.generationNote").value("3 of 5 scenarios generated."));
        assertThat(prompts.stream().filter(ScenarioFixtures::isBuild).count()).isEqualTo(3);
    }
}
