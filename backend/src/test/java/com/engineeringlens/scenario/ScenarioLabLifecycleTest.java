package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.engineeringlens.analysis.review.ReviewRunRepository;
import com.jayway.jsonpath.JsonPath;

/** Creating, reading, finding and cancelling labs through HTTP and the real database, including ownership. */
class ScenarioLabLifecycleTest extends ScenarioFlowSupport {

    @Autowired
    ScenarioLabRepository labs;

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    ScenarioAttemptRepository attempts;

    @Autowired
    ReviewRunRepository reviewRuns;

    private ResultActions create(String auth, String sourceJson, String roles, String seniority, int count) throws Exception {
        return mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{" + sourceJson + ",\"roles\":" + roles + ",\"seniority\":" + seniority + ",\"scenarioCount\":" + count + "}"));
    }

    private String createdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aLabFromAReviewKeepsThatReviewsExactSnapshot() throws Exception {
        String auth = register("lab-review@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String reviewId = review(auth, repoId);
        var review = reviewRuns.findById(UUID.fromString(reviewId)).orElseThrow();

        String labId = createdId(create(auth, "\"reviewId\":\"" + reviewId + "\"", "[\"BACKEND_ENGINEER\",\"CLOUD_ENGINEER\"]",
                "\"SDE2\"", 5));

        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.repositoryId").value(repoId))
                .andExpect(jsonPath("$.repositoryName").value("orders"))
                .andExpect(jsonPath("$.reviewId").value(reviewId))
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.roles[0]").value("BACKEND_ENGINEER"))
                .andExpect(jsonPath("$.roles[1]").value("CLOUD_ENGINEER"))
                .andExpect(jsonPath("$.seniority").value("SDE2"))
                .andExpect(jsonPath("$.scenarioCount").value(5))
                .andExpect(jsonPath("$.scenariosReady").value(5))
                .andExpect(jsonPath("$.scenarios.length()").value(5));

        ScenarioLab lab = labs.findById(UUID.fromString(labId)).orElseThrow();
        assertThat(lab.getAnalysisRunId()).isEqualTo(review.getAnalysisRunId()); // the context the review assessed
        assertThat(lab.getScenarioSchemaVersion()).isEqualTo(Scenario.SCHEMA_VERSION);
        assertThat(lab.getGeneratorVersion()).isEqualTo(ScenarioLab.GENERATOR_VERSION);

        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(labId));
    }

    @Test
    void aLabCanStartDirectlyFromAGitHubLinkWithoutAReview() throws Exception {
        String auth = register("lab-direct@example.com");
        fakeGitHubRepository("asha", "billing");

        String labId = createdId(create(auth, "\"repositoryUrl\":\"https://github.com/asha/billing\"", "[\"BACKEND_ENGINEER\"]",
                "\"SDE1\"", 5));

        String body = mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryName").value("billing"))
                .andExpect(jsonPath("$.reviewId").doesNotExist())
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andReturn().getResponse().getContentAsString();

        // Imported through the normal pipeline: it's in the user's repositories, and importing again returns the same one.
        String repoId = JsonPath.read(body, "$.repositoryId");
        mvc.perform(get("/api/repositories").header("Authorization", auth))
                .andExpect(jsonPath("$[0].id").value(repoId));
        String again = mvc.perform(post("/api/repositories/import").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://github.com/asha/billing\"}")).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.id")).isEqualTo(repoId);
        assertThat(labs.findById(UUID.fromString(labId)).orElseThrow().getAnalysisRunId()).isNotNull();
    }

    @Test
    void aUserHasAtMostOneOpenLabAndCancelledLabsAreNeverActive() throws Exception {
        String auth = register("lab-one@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String source = "\"repositoryId\":\"" + repoId + "\"";

        String first = createdId(create(auth, source, "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5));
        create(auth, source, "[\"FRONTEND_ENGINEER\"]", "\"SDE2\"", 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCENARIO_LAB_ALREADY_ACTIVE"));

        mvc.perform(post("/api/scenario-labs/" + first + "/cancel").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andExpect(status().isNoContent());
        mvc.perform(post("/api/scenario-labs/" + first + "/cancel").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCENARIO_LAB_NOT_ACTIVE"));

        String second = createdId(create(auth, source, "[\"FRONTEND_ENGINEER\"]", "\"SDE2\"", 10));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andExpect(jsonPath("$.id").value(second));
        assertThat(labs.findById(UUID.fromString(first)).orElseThrow().getActiveUserId()).isNull();
    }

    @Test
    void theDatabaseItselfAllowsOnlyOneOpenLabPerUser() throws Exception {
        String auth = register("lab-db@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        ScenarioLab open = labs.findById(UUID.fromString(createdId(create(auth, "\"repositoryId\":\"" + repoId + "\"",
                "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)))).orElseThrow();

        // Even if a service check were skipped (two requests racing), the unique constraint refuses a second open lab.
        assertThatThrownBy(() -> labs.saveAndFlush(new ScenarioLab(open.getUserId(), open.getRepositoryId(), null,
                open.getAnalysisRunId(), COMMIT, List.of(ScenarioRole.BACKEND_ENGINEER), Seniority.SDE1, 5)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aScenarioCanBeSubmittedOnlyOnce() throws Exception {
        String auth = register("lab-attempt@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        ScenarioLab lab = labs.findById(UUID.fromString(createdId(create(auth, "\"repositoryId\":\"" + repoId + "\"",
                "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)))).orElseThrow();
        Scenario scenario = scenarios.saveAndFlush(new Scenario(lab.getId(), 99, ScenarioRole.BACKEND_ENGINEER, Seniority.SDE1,
                ScenarioCategory.ARCHITECTURE_REFACTORING, ScenarioDifficulty.INTERMEDIATE, ExecutionCapability.APPROACH_ONLY, null,
                "Split the order service", "{}", null, null, "{}", null));

        attempts.saveAndFlush(new ScenarioAttempt(lab, scenario, WorkMode.APPROACH, null, "my approach", null));
        assertThatThrownBy(() -> attempts.saveAndFlush(new ScenarioAttempt(lab, scenario, WorkMode.APPROACH, null, "again", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requestsAreValidated() throws Exception {
        String auth = register("lab-validation@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String source = "\"repositoryId\":\"" + repoId + "\"";

        create(auth, source, "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 7)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCENARIO_COUNT"));
        create(auth, source, "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 25)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCENARIO_COUNT"));
        create(auth, source, "[]", "\"SDE1\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.roles").value("Choose at least one role"));
        create(auth, source, "[\"BROAD_ENGINEERING\",\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ROLES"));
        create(auth, source, "[\"WIZARD\"]", "\"SDE1\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        create(auth, source, "[\"BACKEND_ENGINEER\"]", "null", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.seniority").value("Choose a seniority"));
        create(auth, source, "[\"BACKEND_ENGINEER\"]", "\"STAFF\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        create(auth, source + ",\"repositoryUrl\":\"https://github.com/asha/orders\"", "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LAB_SOURCE"));
        create(auth, "\"repositoryUrl\":\"  \"", "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LAB_SOURCE"));
        create(auth, "\"repositoryUrl\":\"https://gitlab.com/asha/orders\"", "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REPOSITORY_URL"));

        // Duplicate roles are merged, not rejected. Nothing failed above, so no lab was created by those requests.
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andExpect(status().isNoContent());
        create(auth, source, "[\"BACKEND_ENGINEER\",\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(1));
    }

    @Test
    void nobodyCanSeeUseOrCancelAnotherUsersLabsRepositoriesOrReviews() throws Exception {
        String asha = register("lab-owner@example.com");
        String repoId = importRepo(asha, "asha", "orders");
        String reviewId = review(asha, repoId);
        String labId = createdId(create(asha, "\"reviewId\":\"" + reviewId + "\"", "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5));

        String mallory = register("lab-intruder@example.com");
        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", mallory))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCENARIO_LAB_NOT_FOUND"));
        mvc.perform(post("/api/scenario-labs/" + labId + "/cancel").header("Authorization", mallory))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCENARIO_LAB_NOT_FOUND"));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", mallory)).andExpect(status().isNoContent());
        create(mallory, "\"reviewId\":\"" + reviewId + "\"", "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        create(mallory, "\"repositoryId\":\"" + repoId + "\"", "[\"BACKEND_ENGINEER\"]", "\"SDE1\"", 5)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // The owner's lab is untouched.
        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", asha)).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/scenario-labs/active")).andExpect(status().isUnauthorized());
    }
}
