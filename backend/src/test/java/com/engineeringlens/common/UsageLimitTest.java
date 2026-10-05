package com.engineeringlens.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.engineeringlens.scenario.ScenarioFlowSupport;

/** Actions that spend shared AI quota are capped per user per day; reusing an existing result costs nothing. */
@TestPropertySource(properties = { "app.limits.reviews-per-day=2", "app.limits.labs-per-day=1" })
class UsageLimitTest extends ScenarioFlowSupport {

    @Test
    void newReviewsAreCappedButAReusedReviewIsFreeAndOtherUsersAreUnaffected() throws Exception {
        String auth = register("limits-reviews@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        review(auth, repoId);
        review(auth, repoId); // same commit, same context: the stored review is returned, no AI call, not counted
        mvc.perform(post("/api/repositories/" + repoId + "/reviews").param("regenerate", "true").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(post("/api/repositories/" + repoId + "/reviews").param("regenerate", "true").header("Authorization", auth))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_REACHED"));

        String other = register("limits-reviews-other@example.com");
        review(other, importRepo(other, "asha", "orders"));
    }

    @Test
    void labsAreCappedPerDay() throws Exception {
        String auth = register("limits-labs@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 5);
        mvc.perform(post("/api/scenario-labs/" + labId + "/cancel").header("Authorization", auth)).andExpect(status().isOk());

        mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repositoryId\":\"" + repoId + "\",\"roles\":[\"BACKEND_ENGINEER\"],\"seniority\":\"SDE1\",\"scenarioCount\":5}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_REACHED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("You've started 1 Scenario Labs in the last 24 hours")));
    }
}
