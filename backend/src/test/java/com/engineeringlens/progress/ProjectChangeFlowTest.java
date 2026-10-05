package com.engineeringlens.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.engineeringlens.analysis.review.ReviewRun;
import com.engineeringlens.analysis.review.ReviewRunRepository;
import com.engineeringlens.analysis.review.ReviewRunStatus;
import com.engineeringlens.scenario.ScenarioFlowSupport;
import com.jayway.jsonpath.JsonPath;

/**
 * The before/after flow on one repository: review at one commit, push an improvement, check for new commits, review
 * again. Progress reports the difference as a project-level change with both reviews as evidence, never as the
 * developer improving (that needs more than one repository or Scenario Lab gains across labs).
 */
class ProjectChangeFlowTest extends ScenarioFlowSupport {

    private static final String LATER = "fedcba9876543210fedcba9876543210fedcba98";

    @Autowired
    ReviewRunRepository reviewRuns;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aReviewAtANewerCommitIsAProjectLevelChangeWithBothReviewsAsEvidence() throws Exception {
        String auth = register("change-flow@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String before = review(auth, repoId); // every dimension Solid

        // Nothing pushed yet: checking changes nothing.
        mvc.perform(post("/api/repositories/" + repoId + "/sync").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.repository.commitSha").value(COMMIT));

        // An improvement is pushed; the repository moves to the new commit.
        moveBranch("asha", "orders", LATER);
        mvc.perform(post("/api/repositories/" + repoId + "/sync").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.previousCommit").value(COMMIT))
                .andExpect(jsonPath("$.repository.commitSha").value(LATER));

        // The next review reads the new commit, and this time rates every dimension Strong.
        answers = p -> p.system().contains("Write advice for THIS developer") ? reviewAnswer(p)
                : reviewAnswer(p).replace("\"assessment\":\"SOLID\"", "\"assessment\":\"STRONG\"");
        String after = review(auth, repoId);
        assertThat(after).isNotEqualTo(before);
        ReviewRun later = reviewRuns.findById(UUID.fromString(after)).orElseThrow();
        assertThat(later.getStatus()).isEqualTo(ReviewRunStatus.COMPLETED);
        assertThat(later.getCommitSha()).isEqualTo(LATER);
        assertThat(reviewRuns.findById(UUID.fromString(before)).orElseThrow().getCommitSha()).isEqualTo(COMMIT); // kept

        String body = mvc.perform(get("/api/progress").param("repositoryId", repoId).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String area = "$.areas[?(@.area == 'SECURITY')]";
        assertThat(JsonPath.<List<String>>read(body, area + ".projectChanges[0].direction")).containsExactly("UP");
        assertThat(JsonPath.<List<String>>read(body, area + ".projectChanges[0].from")).containsExactly("SOLID");
        assertThat(JsonPath.<List<String>>read(body, area + ".projectChanges[0].to")).containsExactly("STRONG");
        assertThat(JsonPath.<List<String>>read(body, area + ".projectChanges[0].fromCommit")).containsExactly(COMMIT);
        assertThat(JsonPath.<List<String>>read(body, area + ".projectChanges[0].toCommit")).containsExactly(LATER);
        // One repository's code got better: a project-level change, not the developer improving.
        assertThat(JsonPath.<List<String>>read(body, "$.areas[*].indicator")).doesNotContain("IMPROVING");
        assertThat(JsonPath.<List<String>>read(body, area + ".evidence[*].reviewId")).containsExactly(after, before);
        assertThat(JsonPath.<List<String>>read(body, area + ".evidence[*].commitSha")).containsExactly(LATER, COMMIT);
        assertThat(JsonPath.<List<Boolean>>read(body, area + ".evidence[*].counted")).containsExactly(true, true);
    }

    @Test
    void checkingForNewCommitsIsOnlyForTheOwnerAndWaitsForRunningWork() throws Exception {
        String auth = register("change-guard@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String mallory = register("change-guard-other@example.com");

        mvc.perform(post("/api/repositories/" + repoId + "/sync").header("Authorization", mallory))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // A review is still running for this repository: the snapshot can't move under it.
        String reviewId = review(auth, repoId);
        jdbc.update("UPDATE review_runs SET status = 'RUNNING' WHERE id = ?", UUID.fromString(reviewId));
        moveBranch("asha", "orders", LATER);
        mvc.perform(post("/api/repositories/" + repoId + "/sync").header("Authorization", auth))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPOSITORY_BUSY"));
        mvc.perform(get("/api/repositories/" + repoId).header("Authorization", auth)).andExpect(jsonPath("$.commitSha").value(COMMIT));
    }
}
