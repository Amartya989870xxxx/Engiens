package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.scenario.generation.ScenarioGenerationWorker;
import com.jayway.jsonpath.JsonPath;

/**
 * 10-scenario labs and progressive generation: scenarios are workable as soon as they're validated, generation can end
 * partially (honestly), an empty lab fails, and a large plan must be varied.
 */
class ScenarioScaleTest extends ScenarioFlowSupport {

    @Autowired
    ScenarioLabRepository labs;

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    ScenarioAttemptRepository attempts;

    @Autowired
    ScenarioGenerationWorker worker;

    private static final String APPROACH = "Make creation idempotent with a key and enforce uniqueness in the database, then add a retry test.";

    private String auth;
    private String repoId;

    private void setUpRepo(String email) throws Exception {
        auth = register(email);
        repoId = importRepo(auth, "asha", "orders");
    }

    private String labUrl(String labId) {
        return "/api/scenario-labs/" + labId;
    }

    @Test
    void aTenScenarioLabIsGeneratedValidatedAndVaried() throws Exception {
        setUpRepo("scale-ten@example.com");
        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        mvc.perform(get(labUrl(labId)).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scenarioCount").value(10))
                .andExpect(jsonPath("$.scenariosReady").value(10))
                .andExpect(jsonPath("$.scenarios.length()").value(10))
                .andExpect(jsonPath("$.generationStage").value("DONE"))
                .andExpect(jsonPath("$.scenariosRejected").value(0))
                .andExpect(jsonPath("$.generationNote").doesNotExist());
        List<Scenario> saved = scenarios.findByLabIdOrderByPositionAsc(UUID.fromString(labId));
        assertThat(saved).extracting(Scenario::getPosition).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        Map<ScenarioCategory, Long> perCategory = saved.stream().collect(Collectors.groupingBy(Scenario::getCategory, Collectors.counting()));
        assertThat(perCategory.values()).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(3));
        AiPrompt plan = prompts.stream().filter(ScenarioFixtures::isPlan).findFirst().orElseThrow();
        assertThat(plan.system()).contains("Propose exactly 14 scenario outlines", "This is a large lab: at most 3 outlines may share a category");
    }

    @Test
    void aMonotonousPlanForALargeLabIsRejectedAndRepaired() throws Exception {
        setUpRepo("scale-diversity@example.com");
        AtomicInteger plans = new AtomicInteger();
        answers = p -> ScenarioFixtures.isPlan(p) && plans.incrementAndGet() == 1
                ? ScenarioFixtures.plan(p).replaceAll("\"category\":\"[A-Z_]+\"", "\"category\":\"SECURITY\"") : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        assertThat(labs.findById(UUID.fromString(labId)).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.ACTIVE);
        assertThat(prompts.stream().filter(ScenarioFixtures::isPlan).toList().get(1).user())
                .contains("at most 3 outlines may share a category");
    }

    @Test
    void readyScenariosCanBeSolvedWhileTheRestAreStillGenerating() throws Exception {
        setUpRepo("scale-progressive@example.com");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger builds = new AtomicInteger();
        answers = p -> {
            if (ScenarioFixtures.isBuild(p) && builds.incrementAndGet() == 3) {
                // Two scenarios are saved, the lab is still generating: the user works on the first one now.
                try {
                    workOnFirstReadyScenario(seen);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return defaultAnswer(p);
        };

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        assertThat(seen).containsExactly("lab GENERATING with 2 ready", "detail 200", "draft 204", "run 200 FAILED", "submit 200",
                "feedback 200", "finish 409 SCENARIO_LAB_NOT_ACTIVE");
        // Generation carried on and finished; the submission made meanwhile is intact and was not completed early.
        mvc.perform(get(labUrl(labId)).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scenarios.length()").value(10))
                .andExpect(jsonPath("$.scenarios[0].submitted").value(true));
        assertThat(attempts.findByLabIdOrderByCreatedAtAsc(UUID.fromString(labId))).hasSize(1);
    }

    private void workOnFirstReadyScenario(List<String> seen) throws Exception {
        String lab = mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andReturn().getResponse().getContentAsString();
        String labId = JsonPath.read(lab, "$.id");
        List<String> ids = JsonPath.read(lab, "$.scenarios[*].id");
        seen.add("lab " + JsonPath.read(lab, "$.status") + " with " + ids.size() + " ready");
        String url = labUrl(labId) + "/scenarios/" + ids.get(0);
        String files = "[{\"path\":\"orders.py\",\"content\":" + ScenarioFixtures.JSON.writeValueAsString(ScenarioFixtures.STARTER) + "}]";
        seen.add("detail " + mvc.perform(get(url).header("Authorization", auth)).andReturn().getResponse().getStatus());
        seen.add("draft " + mvc.perform(put(url + "/draft").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"APPROACH\",\"approach\":\"thinking\"}")).andReturn().getResponse().getStatus());
        var run = mvc.perform(post(url + "/run").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":" + files + "}")).andReturn().getResponse();
        seen.add("run " + run.getStatus() + " " + JsonPath.read(run.getContentAsString(), "$.status"));
        seen.add("submit " + mvc.perform(post(url + "/submit").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"APPROACH\",\"approach\":\"" + APPROACH + "\"}")).andReturn().getResponse().getStatus());
        seen.add("feedback " + mvc.perform(get(url + "/feedback").header("Authorization", auth)).andReturn().getResponse().getStatus());
        var finish = mvc.perform(post(labUrl(labId) + "/finish").header("Authorization", auth)).andReturn().getResponse();
        seen.add("finish " + finish.getStatus() + " " + JsonPath.read(finish.getContentAsString(), "$.code"));
    }

    @Test
    void whenGenerationEndsPartiallyEveryValidatedScenarioIsKeptAndTheLabSaysSo() throws Exception {
        setUpRepo("scale-partial@example.com");
        AtomicInteger builds = new AtomicInteger();
        // After three scenarios every model stops answering usefully: an outage mid-generation.
        answers = p -> ScenarioFixtures.isBuild(p) && builds.incrementAndGet() > 3 ? "not json" : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        String body = mvc.perform(get(labUrl(labId)).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scenarios.length()").value(3))
                .andExpect(jsonPath("$.generationNote").value("3 of 10 scenarios generated."))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.scenarios[*].id");

        answers = ScenarioFlowSupport::defaultAnswer;
        mvc.perform(post(labUrl(labId) + "/scenarios/" + ids.get(0) + "/submit").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"APPROACH\",\"approach\":\"" + APPROACH + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(labUrl(labId) + "/finish").header("Authorization", auth)).andExpect(status().isAccepted());

        mvc.perform(get("/api/repositories/" + repoId + "/scenario-labs").header("Authorization", auth))
                .andExpect(jsonPath("$[0].scenarioCount").value(10))
                .andExpect(jsonPath("$[0].scenariosGenerated").value(3))
                .andExpect(jsonPath("$[0].scenariosCompleted").value(1));
        mvc.perform(get(labUrl(labId) + "/assessment").header("Authorization", auth))
                .andExpect(jsonPath("$.scenariosGenerated").value(3))
                .andExpect(jsonPath("$.generationNote").value("3 of 10 scenarios generated."))
                .andExpect(jsonPath("$.scenarios.length()").value(1))
                .andExpect(jsonPath("$.assessment.limitations[0]").value("Only 3 of the 10 requested scenarios could be generated."))
                .andExpect(jsonPath("$.assessment.limitations[1]").value("Finished early: 1 of 3 scenarios were submitted, and only those are assessed."));
    }

    @Test
    void ifEveryScenarioThatExistsIsAlreadySubmittedWhenGenerationEndsTheLabCompletes() throws Exception {
        setUpRepo("scale-autocomplete@example.com");
        AtomicInteger builds = new AtomicInteger();
        answers = p -> {
            if (ScenarioFixtures.isBuild(p) && builds.incrementAndGet() == 3) {
                try {
                    submitEveryReadyScenario();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return ScenarioFixtures.isBuild(p) && builds.get() >= 3 ? "not json" : defaultAnswer(p);
        };

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        mvc.perform(get(labUrl(labId)).header("Authorization", auth)).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andExpect(status().isNoContent());
    }

    private void submitEveryReadyScenario() throws Exception {
        String lab = mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andReturn().getResponse().getContentAsString();
        String labId = JsonPath.read(lab, "$.id");
        List<String> ids = JsonPath.read(lab, "$.scenarios[*].id");
        for (String id : ids) {
            mvc.perform(post(labUrl(labId) + "/scenarios/" + id + "/submit").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mode\":\"APPROACH\",\"approach\":\"" + APPROACH + "\"}"));
        }
        // Not completed yet: the lab is still generating.
        assertThat(labs.findById(UUID.fromString(labId)).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.GENERATING);
    }

    @Test
    void aLabWithNoScenarioAtAllFailsInsteadOfOpeningEmpty() throws Exception {
        setUpRepo("scale-zero@example.com");
        answers = p -> ScenarioFixtures.isBuild(p) ? "not json" : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 10);

        mvc.perform(get(labUrl(labId)).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.scenarios.length()").value(0));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andExpect(status().isNoContent());
    }

    @Test
    void aRestartKeepsTheScenariosAlreadyValidated() throws Exception {
        setUpRepo("scale-restart@example.com");
        String doneId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"", 5);
        ScenarioLab done = labs.findById(UUID.fromString(doneId)).orElseThrow();
        done.cancel();
        labs.save(done);
        // A lab cut off by a restart after two validated scenarios.
        ScenarioLab stuck = labs.save(new ScenarioLab(done.getUserId(), done.getRepositoryId(), null, done.getAnalysisRunId(), COMMIT,
                List.of(ScenarioRole.BACKEND_ENGINEER), Seniority.SDE1, 10));
        for (int i = 1; i <= 2; i++) {
            Scenario s = scenarios.findByLabIdOrderByPositionAsc(done.getId()).get(i - 1);
            scenarios.save(new Scenario(stuck.getId(), i, s.getRole(), s.getSeniority(), s.getCategory(), s.getDifficulty(),
                    s.getExecutionCapability(), s.getLanguage(), s.getTitle(), s.getScenarioJson(), s.getWorkspaceJson(), s.getHarnessJson(),
                    s.getReferenceJson(), s.getValidationJson()));
            ScenarioLab fresh = labs.findById(stuck.getId()).orElseThrow();
            fresh.scenarioReady();
            labs.save(fresh);
        }

        worker.failInterruptedGeneration();

        ScenarioLab after = labs.findById(stuck.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ScenarioLabStatus.ACTIVE);
        assertThat(after.getGenerationNote()).isEqualTo("2 of 10 scenarios generated.");
        assertThat(after.scenariosAvailable()).isEqualTo(2);
    }

    @Test
    void setupCanAskWhetherThePreferredModelIsAvailable() throws Exception {
        setUpRepo("scale-capacity@example.com");
        mvc.perform(get("/api/scenario-labs/capacity").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preferredModelAvailable").value(true));
    }
}
