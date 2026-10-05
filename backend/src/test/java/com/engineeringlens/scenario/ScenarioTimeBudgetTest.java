package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** No build starts after a lab's deadline; with no time at all nothing is built, and an empty lab fails. */
@TestPropertySource(properties = { "scenario.generation.deadline-base=PT0S", "scenario.generation.deadline-per-scenario=PT0S" })
class ScenarioTimeBudgetTest extends ScenarioFlowSupport {

    @Test
    void noBuildStartsAfterTheDeadline() throws Exception {
        String auth = register("deadline@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("SCENARIO_GENERATION_FAILED"));
        assertThat(prompts.stream().filter(ScenarioFixtures::isBuild).count()).isZero();
    }
}
