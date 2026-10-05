package com.engineeringlens.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.engineeringlens.scenario.ScenarioFixtures;
import com.engineeringlens.scenario.ScenarioFlowSupport;
import com.jayway.jsonpath.JsonPath;

/**
 * Progress through HTTP and the real database: only the user's own completed reviews and labs count, every
 * indicator carries its evidence, and nothing is written or regenerated to produce it.
 */
class ProgressFlowTest extends ScenarioFlowSupport {

    private static final String APPROACH = "Make creation idempotent with a key and enforce uniqueness in the database, then add a retry test.";

    @Autowired
    JdbcTemplate jdbc;

    private String progress(String auth) throws Exception {
        return mvc.perform(get("/api/progress").header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** A completed lab with one submitted answer, finished early. */
    private String completedLab(String auth, String repoId) throws Exception {
        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 5);
        submitFirst(auth, labId);
        mvc.perform(post("/api/scenario-labs/" + labId + "/finish").header("Authorization", auth)).andExpect(status().isAccepted());
        return labId;
    }

    private void submitFirst(String auth, String labId) throws Exception {
        String lab = mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth)).andReturn().getResponse().getContentAsString();
        String scenarioId = JsonPath.<List<String>>read(lab, "$.scenarios[*].id").get(0);
        mvc.perform(post("/api/scenario-labs/" + labId + "/scenarios/" + scenarioId + "/submit").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"APPROACH\",\"approach\":\"" + APPROACH + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aNewUserHasNoEvidenceAndEveryAreaIsNotAssessed() throws Exception {
        String auth = register("progress-empty@example.com");

        mvc.perform(get("/api/progress").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.reviews").value(0))
                .andExpect(jsonPath("$.evidence.labs").value(0))
                .andExpect(jsonPath("$.repositories.length()").value(0))
                .andExpect(jsonPath("$.areas.length()").value(16))
                .andExpect(jsonPath("$.areas[?(@.indicator != 'NOT_ASSESSED')]").isEmpty())
                .andExpect(jsonPath("$.nextAreas").isEmpty())
                .andExpect(jsonPath("$.recommendations.fromReview").doesNotExist());
        mvc.perform(get("/api/progress/history").header("Authorization", auth))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void oneReviewIsShownWithItsEvidenceButIsNotEnoughHistoryForAnyIndicator() throws Exception {
        String auth = register("progress-one@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String reviewId = review(auth, repoId);

        String body = progress(auth);

        assertThat(JsonPath.<Integer>read(body, "$.evidence.reviews")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(body, "$.areas[*].indicator")).containsOnly("NOT_ENOUGH_HISTORY");
        assertThat(JsonPath.<String>read(body, "$.areas[0].reason"))
                .isEqualTo("Assessed on one occasion (1 review). Not enough history to identify a trend.");
        assertThat(JsonPath.<String>read(body, "$.areas[0].evidence[0].reviewId")).isEqualTo(reviewId);
        assertThat(JsonPath.<String>read(body, "$.areas[0].evidence[0].repositoryName")).isEqualTo("orders");
        assertThat(JsonPath.<String>read(body, "$.areas[0].evidence[0].commitSha")).isEqualTo(COMMIT);
        assertThat(JsonPath.<String>read(body, "$.areas[0].evidence[0].level")).isEqualTo("SOLID");
        assertThat(JsonPath.<String>read(body, "$.recommendations.fromReview.reviewId")).isEqualTo(reviewId);
        assertThat(JsonPath.<List<Object>>read(body, "$.recommendations.fromReview.topics")).isNotEmpty();
    }

    @Test
    void aReviewAndACompletedLabTogetherGiveAnIndicatorBackedByBothKindsOfEvidence() throws Exception {
        String auth = register("progress-both@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        review(auth, repoId);
        String labId = completedLab(auth, repoId);
        int promptsBefore = prompts.size();

        String body = progress(auth);

        assertThat(prompts).hasSize(promptsBefore); // no model call to produce Progress
        // The first fixture scenario is a concurrency problem: the review and the answer both rated it Solid.
        String concurrency = "$.areas[?(@.area == 'CONCURRENCY_AND_CONSISTENCY')]";
        assertThat(JsonPath.<List<String>>read(body, concurrency + ".indicator")).containsExactly("CONSISTENT_STRENGTH");
        assertThat(JsonPath.<List<String>>read(body, concurrency + ".reason"))
                .containsExactly("Solid or Strong in 2 of 2 assessments (1 review and 1 Scenario Lab answer), including the latest.");
        assertThat(JsonPath.<List<String>>read(body, concurrency + ".evidence[*].source")).containsExactly("SCENARIO", "REVIEW");
        assertThat(JsonPath.<List<String>>read(body, concurrency + ".evidence[0].labId")).containsExactly(labId);
        assertThat(JsonPath.<List<String>>read(body, concurrency + ".evidence[0].category")).containsExactly("CONCURRENCY_CONSISTENCY");
        assertThat(JsonPath.<Integer>read(body, "$.evidence.evaluatedAnswers")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(body, "$.practice.categories[*].category")).containsExactly("CONCURRENCY_CONSISTENCY");
        assertThat(JsonPath.<Integer>read(body, "$.practice.categories[0].verdicts.SOLID")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(body, "$.practice.roles[*].value")).containsExactly("BACKEND_ENGINEER");
        assertThat(JsonPath.<List<String>>read(body, "$.practice.seniorities[*].value")).containsExactly("SDE2");
        // Never source code: no workspace, submission or reference content.
        assertThat(body).doesNotContain(ScenarioFixtures.STARTER.strip()).doesNotContain("# FIXED").doesNotContain(APPROACH);

        String history = mvc.perform(get("/api/progress/history").header("Authorization", auth)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(history, "$.items[*].type")).containsExactly("LAB", "REVIEW");
        assertThat(JsonPath.<Integer>read(history, "$.items[0].scenariosSubmitted")).isEqualTo(1);
        assertThat(JsonPath.<String>read(history, "$.items[1].overall")).isNotBlank();
    }

    @Test
    void openDiscardedAndFailedWorkIsNotEvidence() throws Exception {
        String auth = register("progress-excluded@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        // A failed review: the model never returns a usable review.
        answers = p -> ScenarioFixtures.isPlan(p) || ScenarioFixtures.isBuild(p) || ScenarioFixtures.isAssess(p) ? defaultAnswer(p) : "not json";
        mvc.perform(post("/api/repositories/" + repoId + "/reviews").header("Authorization", auth));
        answers = ScenarioFlowSupport::defaultAnswer;
        // A discarded lab with a submitted answer.
        String discarded = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 5);
        submitFirst(auth, discarded);
        mvc.perform(post("/api/scenario-labs/" + discarded + "/cancel").header("Authorization", auth)).andExpect(status().isOk());
        // An open lab with a submitted, evaluated answer.
        submitFirst(auth, startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 5));

        mvc.perform(get("/api/progress").header("Authorization", auth))
                .andExpect(jsonPath("$.evidence.reviews").value(0))
                .andExpect(jsonPath("$.evidence.labs").value(0))
                .andExpect(jsonPath("$.evidence.evaluatedAnswers").value(0))
                .andExpect(jsonPath("$.areas[?(@.indicator != 'NOT_ASSESSED')]").isEmpty());
    }

    @Test
    void progressAcrossRepositoriesAndForOneRepository() throws Exception {
        String auth = register("progress-scope@example.com");
        String orders = importRepo(auth, "asha", "orders");
        String payments = importRepo(auth, "asha", "payments");
        review(auth, orders);
        review(auth, payments);

        String all = progress(auth);
        assertThat(JsonPath.<Integer>read(all, "$.evidence.repositories")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(all, "$.repositories[*].name")).containsExactlyInAnyOrder("orders", "payments");
        assertThat(JsonPath.<String>read(all, "$.areas[0].indicator")).isEqualTo("CONSISTENT_STRENGTH");
        assertThat(JsonPath.<String>read(all, "$.areas[0].reason")).endsWith("(2 reviews across 2 repositories), including the latest.");

        mvc.perform(get("/api/progress").param("repositoryId", orders).header("Authorization", auth))
                .andExpect(jsonPath("$.scope.repositoryName").value("orders"))
                .andExpect(jsonPath("$.evidence.repositories").value(1))
                .andExpect(jsonPath("$.areas[0].indicator").value("NOT_ENOUGH_HISTORY"));
        mvc.perform(get("/api/progress/history").param("repositoryId", payments).header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].repositoryName").value("payments"));
    }

    @Test
    void anotherUsersRepositoryCantBeUsedAsAScopeAndTheirEvidenceIsNeverShown() throws Exception {
        String asha = register("progress-owner@example.com");
        String repoId = importRepo(asha, "asha", "orders");
        review(asha, repoId);
        String mallory = register("progress-other@example.com");

        mvc.perform(get("/api/progress").param("repositoryId", repoId).header("Authorization", mallory))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));
        mvc.perform(get("/api/progress/history").param("repositoryId", repoId).header("Authorization", mallory))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/progress").header("Authorization", mallory))
                .andExpect(jsonPath("$.evidence.reviews").value(0)).andExpect(jsonPath("$.repositories.length()").value(0));
        mvc.perform(get("/api/progress")).andExpect(status().isUnauthorized());
    }

    @Test
    void aStoredReviewThatCantBeReadIsSkippedAndCountedNotFatal() throws Exception {
        String auth = register("progress-unreadable@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String reviewId = review(auth, repoId);
        jdbc.update("UPDATE reviews SET review_json = ? WHERE review_run_id = ?", "{not json", java.util.UUID.fromString(reviewId));

        mvc.perform(get("/api/progress").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.reviews").value(0))
                .andExpect(jsonPath("$.evidence.skipped").value(1));
    }
}
