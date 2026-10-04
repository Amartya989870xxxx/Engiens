package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.scenario.execution.ExecutionUnavailableException;
import com.jayway.jsonpath.JsonPath;

/**
 * The open workspace and the way out of it: draft → run → submit → evaluate → complete → history, through HTTP
 * and the real database, with the fake model and sandbox from {@link ScenarioFlowSupport}.
 */
class ScenarioWorkspaceFlowTest extends ScenarioFlowSupport {

    @Autowired
    ScenarioLabRepository labs;

    @Autowired
    ScenarioAttemptRepository attempts;

    private record Lab(String auth, String repoId, String reviewId, String labId, List<String> scenarioIds) {
    }

    private Lab lab(String email) throws Exception {
        String auth = register(email);
        String repoId = importRepo(auth, "asha", "orders");
        String reviewId = review(auth, repoId);
        String body = mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewId\":\"" + reviewId + "\",\"roles\":[\"BACKEND_ENGINEER\"],\"seniority\":\"SDE2\",\"scenarioCount\":5}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Lab(auth, repoId, reviewId, JsonPath.read(body, "$.id"), JsonPath.read(body, "$.scenarios[*].id"));
    }

    private String scenarioUrl(Lab lab, int i) {
        return "/api/scenario-labs/" + lab.labId() + "/scenarios/" + lab.scenarioIds().get(i);
    }

    private static String files(String content) {
        return "[{\"path\":\"orders.py\",\"content\":" + ScenarioFixtures.JSON.writeValueAsString(content) + "}]";
    }

    private ResultActions submit(Lab lab, int i, String mode, String content, String approach) throws Exception {
        return mvc.perform(post(scenarioUrl(lab, i) + "/submit").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"" + mode + "\",\"files\":" + (content == null ? "null" : files(content)) + ",\"approach\":"
                        + (approach == null ? "null" : ScenarioFixtures.JSON.writeValueAsString(approach)) + "}"));
    }

    private static final String APPROACH = "Make creation idempotent with a key and enforce uniqueness in the database, then add a retry test.";

    @Test
    void draftRunSubmitEvaluateCompleteAndReadTheHistory() throws Exception {
        Lab lab = lab("ws-flow@example.com");

        // The workspace: starter code, opens in code mode, no hidden checks or reference.
        String detail = mvc.perform(get(scenarioUrl(lab, 0)).header("Authorization", lab.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryName").value("orders"))
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.draftMode").value("CODE"))
                .andExpect(jsonPath("$.files[0].path").value("orders.py"))
                .andExpect(jsonPath("$.files[0].editable").value(true))
                .andExpect(jsonPath("$.files[0].content").value(ScenarioFixtures.STARTER))
                .andExpect(jsonPath("$.document.task").exists())
                .andExpect(jsonPath("$.submitted").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("HIDDEN_CHECK_SOURCE").doesNotContain("# FIXED").doesNotContain("referenceReasoning");

        // Autosave keeps code and approach independently across mode switches (and a refresh).
        mvc.perform(put(scenarioUrl(lab, 0) + "/draft").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CODE\",\"files\":" + files("x = 'my edit'\n") + "}")).andExpect(status().isNoContent());
        mvc.perform(put(scenarioUrl(lab, 0) + "/draft").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"APPROACH\",\"approach\":\"My first thoughts\"}")).andExpect(status().isNoContent());
        mvc.perform(get(scenarioUrl(lab, 0)).header("Authorization", lab.auth()))
                .andExpect(jsonPath("$.draftMode").value("APPROACH"))
                .andExpect(jsonPath("$.draftApproach").value("My first thoughts"))
                .andExpect(jsonPath("$.files[0].content").value("x = 'my edit'\n"))
                .andExpect(jsonPath("$.files[0].starterContent").value(ScenarioFixtures.STARTER));

        // Run is objective feedback with named checks.
        mvc.perform(post(scenarioUrl(lab, 0) + "/run").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":" + files(ScenarioFixtures.STARTER) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.passed").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.checks[1].name").value("ignores a repeated request"))
                .andExpect(jsonPath("$.checks[1].message").value("a repeated request created a second order"));
        mvc.perform(post(scenarioUrl(lab, 0) + "/run").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":" + files(ScenarioFixtures.FIXED) + "}"))
                .andExpect(jsonPath("$.status").value("PASSED")).andExpect(jsonPath("$.passed").value(3));

        // Submit freezes the work, runs the checks once more and evaluates it.
        submit(lab, 0, "CODE", ScenarioFixtures.FIXED, "Return the existing order on a retry.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CODE"))
                .andExpect(jsonPath("$.runResult.status").value("PASSED"))
                .andExpect(jsonPath("$.evaluationStatus").value("COMPLETED"));
        submit(lab, 0, "CODE", ScenarioFixtures.FIXED, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_SUBMISSION_ALREADY_COMPLETED"));
        mvc.perform(post(scenarioUrl(lab, 0) + "/run").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":" + files("y") + "}")).andExpect(status().isConflict());
        mvc.perform(get("/api/scenario-labs/" + lab.labId()).header("Authorization", lab.auth()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scenarios[0].submitted").value(true))
                .andExpect(jsonPath("$.scenarios[0].evaluationStatus").value("COMPLETED"));
        mvc.perform(get("/api/repositories/" + lab.repoId() + "/scenario-labs").header("Authorization", lab.auth()))
                .andExpect(jsonPath("$.length()").value(0)); // an open lab is never history

        // A code scenario can also be answered in approach mode; the rest complete the lab.
        for (int i = 1; i < 5; i++) {
            submit(lab, i, "APPROACH", null, APPROACH).andExpect(status().isOk());
        }

        // Completed: the workspace closes, Scenario Lab is empty again, and the assessment is history.
        mvc.perform(get("/api/scenario-labs/" + lab.labId()).header("Authorization", lab.auth())).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", lab.auth())).andExpect(status().isNoContent());
        mvc.perform(get(scenarioUrl(lab, 1)).header("Authorization", lab.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_LAB_NOT_ACTIVE"));

        mvc.perform(get("/api/repositories/" + lab.repoId() + "/scenario-labs").header("Authorization", lab.auth()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(lab.labId()))
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].roles[0]").value("BACKEND_ENGINEER"))
                .andExpect(jsonPath("$[0].seniority").value("SDE2"))
                .andExpect(jsonPath("$[0].scenariosCompleted").value(5))
                .andExpect(jsonPath("$[0].reviewId").value(lab.reviewId()))
                .andExpect(jsonPath("$[0].assessment").doesNotExist()); // compact: no bodies

        String report = mvc.perform(get("/api/scenario-labs/" + lab.labId() + "/assessment").header("Authorization", lab.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryName").value("orders"))
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.assessment.overallAssessment.engineeringLevel").exists())
                .andExpect(jsonPath("$.assessment.learningRecommendations[0].topic").value("Idempotency"))
                .andExpect(jsonPath("$.assessment.personalizedFor").value("Undergraduate, year 2"))
                .andExpect(jsonPath("$.scenarios.length()").value(5))
                .andExpect(jsonPath("$.scenarios[0].mode").value("CODE"))
                .andExpect(jsonPath("$.scenarios[0].submittedFiles[0].content").value(ScenarioFixtures.FIXED))
                .andExpect(jsonPath("$.scenarios[0].runResult.passed").value(3))
                .andExpect(jsonPath("$.scenarios[0].evaluation.verdict").value("SOLID"))
                .andExpect(jsonPath("$.scenarios[0].learningPoints[0]").exists())
                .andExpect(jsonPath("$.scenarios[0].reference.referenceReasoning").exists())
                .andExpect(jsonPath("$.scenarios[1].mode").value("APPROACH"))
                .andExpect(jsonPath("$.scenarios[1].submittedApproach").value(APPROACH))
                .andReturn().getResponse().getContentAsString();
        assertThat(report).doesNotContain("HIDDEN_CHECK_SOURCE");

        // Assess first, teach second: verdicts never see the developer, teaching never sees code.
        List<AiPrompt> assess = prompts.stream().filter(ScenarioFixtures::isAssess).toList();
        AiPrompt summary = prompts.stream().filter(ScenarioFixtures::isSummary).findFirst().orElseThrow();
        AiPrompt teaching = prompts.stream().filter(ScenarioFixtures::isLabTeaching).findFirst().orElseThrow();
        assertThat(assess).hasSize(5);
        assertThat(assess.get(0).user()).contains("# FIXED", "PASS ignores a repeated request", "Return the existing order on a retry.");
        assertThat(assess).allSatisfy(p -> assertThat(p.system() + p.user()).doesNotContain("UNDERGRADUATE").doesNotContain("Asha"));
        assertThat(summary.system() + summary.user()).doesNotContain("UNDERGRADUATE").doesNotContain("# FIXED");
        assertThat(teaching.user()).contains("UNDERGRADUATE").doesNotContain("# FIXED").doesNotContain("def place");
    }

    @Test
    void submissionsAreValidated() throws Exception {
        Lab lab = lab("ws-validation@example.com");
        submit(lab, 0, "APPROACH", null, "too short").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("APPROACH_REQUIRED"));
        mvc.perform(post(scenarioUrl(lab, 0) + "/run").header("Authorization", lab.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[{\"path\":\"engiens_checks.py\",\"content\":\"assert True\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WORKSPACE"));
        submit(lab, 0, null, null, null).andExpect(status().isBadRequest());
        mvc.perform(get("/api/scenario-labs/" + lab.labId() + "/scenarios/" + UUID.randomUUID()).header("Authorization", lab.auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCENARIO_NOT_FOUND"));
        mvc.perform(get("/api/scenario-labs/" + lab.labId() + "/assessment").header("Authorization", lab.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_ASSESSMENT_NOT_READY"));
    }

    @Test
    void nobodyElseCanReadEditRunSubmitOrSeeTheHistory() throws Exception {
        Lab lab = lab("ws-owner@example.com");
        String mallory = register("ws-intruder@example.com");
        mvc.perform(get(scenarioUrl(lab, 0)).header("Authorization", mallory)).andExpect(status().isNotFound());
        mvc.perform(put(scenarioUrl(lab, 0) + "/draft").header("Authorization", mallory).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"APPROACH\",\"approach\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post(scenarioUrl(lab, 0) + "/run").header("Authorization", mallory).contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":" + files("x") + "}")).andExpect(status().isNotFound());
        mvc.perform(post(scenarioUrl(lab, 0) + "/submit").header("Authorization", mallory).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"APPROACH\",\"approach\":\"" + APPROACH + "\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/scenario-labs/" + lab.labId() + "/assessment").header("Authorization", mallory)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + lab.repoId() + "/scenario-labs").header("Authorization", mallory))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));
        assertThat(attempts.findByLabIdOrderByCreatedAtAsc(UUID.fromString(lab.labId()))).isEmpty();
    }

    @Test
    void aFailedEvaluationKeepsTheSubmissionAndCanBeRetried() throws Exception {
        Lab lab = lab("ws-retry@example.com");
        answers = p -> ScenarioFixtures.isAssess(p) ? "not json" : defaultAnswer(p);

        submit(lab, 0, "APPROACH", null, APPROACH)
                .andExpect(jsonPath("$.evaluationStatus").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("AI_UNAVAILABLE"));
        submit(lab, 0, "APPROACH", null, APPROACH).andExpect(status().isConflict()); // still submitted exactly once

        answers = ScenarioFlowSupport::defaultAnswer;
        mvc.perform(post(scenarioUrl(lab, 0) + "/attempt/retry-evaluation").header("Authorization", lab.auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evaluationStatus").value("COMPLETED"));
        assertThat(attempts.findByLabIdOrderByCreatedAtAsc(UUID.fromString(lab.labId()))).hasSize(1)
                .allSatisfy(a -> assertThat(a.getSubmittedApproach()).isEqualTo(APPROACH));
    }

    @Test
    void ifTheFinalAssessmentCantBeWrittenTheLabWaitsAndCanBeFinalisedLater() throws Exception {
        Lab lab = lab("ws-finalize@example.com");
        answers = p -> ScenarioFixtures.isSummary(p) ? "not json" : defaultAnswer(p);
        for (int i = 0; i < 5; i++) {
            submit(lab, i, "APPROACH", null, APPROACH).andExpect(status().isOk());
        }
        mvc.perform(get("/api/scenario-labs/" + lab.labId()).header("Authorization", lab.auth()))
                .andExpect(jsonPath("$.status").value("FINALIZING"))
                .andExpect(jsonPath("$.errorCode").value("AI_UNAVAILABLE"));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", lab.auth())).andExpect(jsonPath("$.id").value(lab.labId()));

        answers = ScenarioFlowSupport::defaultAnswer;
        mvc.perform(post("/api/scenario-labs/" + lab.labId() + "/finalize").header("Authorization", lab.auth())).andExpect(status().isAccepted());
        mvc.perform(get("/api/scenario-labs/" + lab.labId()).header("Authorization", lab.auth())).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/scenario-labs/" + lab.labId() + "/finalize").header("Authorization", lab.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_LAB_NOT_FINALIZING"));
    }

    @Test
    void withoutPersonalisationTheAssessmentStillCompletesAndSaysSo() throws Exception {
        Lab lab = lab("ws-noteach@example.com");
        answers = p -> ScenarioFixtures.isLabTeaching(p) ? "not json" : defaultAnswer(p);
        for (int i = 0; i < 5; i++) {
            submit(lab, i, "APPROACH", null, APPROACH);
        }
        mvc.perform(get("/api/scenario-labs/" + lab.labId() + "/assessment").header("Authorization", lab.auth()))
                .andExpect(jsonPath("$.assessment.personalizedFor").doesNotExist())
                .andExpect(jsonPath("$.assessment.limitations[1]").value(
                        "Personalised learning points couldn't be generated this time. The assessment is unaffected."))
                .andExpect(jsonPath("$.assessment.overallAssessment.summary").exists());
    }

    @Test
    void aSubmissionIsKeptEvenWhenTheSandboxIsDownAtThatMoment() throws Exception {
        Lab lab = lab("ws-nosandbox@example.com");
        doThrow(new ExecutionUnavailableException("docker stopped")).when(sandbox).execute(any());
        submit(lab, 0, "CODE", ScenarioFixtures.FIXED, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runResult").doesNotExist())
                .andExpect(jsonPath("$.evaluationStatus").value("COMPLETED"));
        assertThat(prompts.stream().filter(ScenarioFixtures::isAssess).findFirst().orElseThrow().user())
                .contains("Not available: the sandbox couldn't run this submission.");
    }

    @Test
    void everyLabIsItsOwnHistoryEntry() throws Exception {
        Lab first = lab("ws-history@example.com");
        for (int i = 0; i < 5; i++) {
            submit(first, i, "APPROACH", null, APPROACH);
        }
        String body = mvc.perform(post("/api/scenario-labs").header("Authorization", first.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repositoryId\":\"" + first.repoId() + "\",\"roles\":[\"CLOUD_ENGINEER\"],\"seniority\":\"SDE3\",\"scenarioCount\":5}"))
                .andReturn().getResponse().getContentAsString();
        Lab second = new Lab(first.auth(), first.repoId(), null, JsonPath.read(body, "$.id"), JsonPath.read(body, "$.scenarios[*].id"));
        for (int i = 0; i < 5; i++) {
            submit(second, i, "APPROACH", null, APPROACH);
        }

        mvc.perform(get("/api/repositories/" + first.repoId() + "/scenario-labs").header("Authorization", first.auth()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.labId()))
                .andExpect(jsonPath("$[0].number").value(2))
                .andExpect(jsonPath("$[0].roles[0]").value("CLOUD_ENGINEER"))
                .andExpect(jsonPath("$[0].reviewId").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(first.labId()))
                .andExpect(jsonPath("$[1].number").value(1));
        assertThat(labs.findById(UUID.fromString(first.labId())).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.COMPLETED);
    }
}
