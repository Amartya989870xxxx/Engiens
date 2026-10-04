package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.scenario.generation.ScenarioGenerationWorker;
import com.engineeringlens.scenario.model.ScenarioValidation;
import com.jayway.jsonpath.JsonPath;

/** Plan → build → validate (including the sandbox harness) → persist, with a fake model and a fake sandbox. */
class ScenarioGenerationTest extends ScenarioFlowSupport {

    @Autowired
    ScenarioLabRepository labs;

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    ScenarioGenerationWorker worker;

    private String startLab(String auth, String source) throws Exception {
        String body = mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{" + source + ",\"roles\":[\"BACKEND_ENGINEER\"],\"seniority\":\"SDE2\",\"scenarioCount\":5}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private List<AiPrompt> generationPrompts() {
        return prompts.stream().filter(p -> ScenarioFixtures.isPlan(p) || ScenarioFixtures.isBuild(p)).toList();
    }

    @Test
    void aLabFromAReviewGetsValidatedRepositorySpecificScenarios() throws Exception {
        String auth = register("gen-review@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String reviewId = review(auth, repoId);
        prompts.clear();

        String labId = startLab(auth, "\"reviewId\":\"" + reviewId + "\"");

        String body = mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scenariosReady").value(5))
                .andExpect(jsonPath("$.scenarios.length()").value(5))
                .andExpect(jsonPath("$.scenarios[0].executionCapability").value("CODE"))
                .andExpect(jsonPath("$.scenarios[0].language").value("PYTHON"))
                .andExpect(jsonPath("$.scenarios[0].role").value("BACKEND_ENGINEER"))
                .andExpect(jsonPath("$.scenarios[0].submitted").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("HIDDEN_CHECK_SOURCE").doesNotContain("# FIXED"); // no checks or reference leak

        List<Scenario> saved = scenarios.findByLabIdOrderByPositionAsc(UUID.fromString(labId));
        assertThat(saved).extracting(Scenario::getPosition).containsExactly(1, 2, 3, 4, 5);
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.getSeniority()).isEqualTo(Seniority.SDE2);
            assertThat(s.getDraftMode()).isEqualTo(WorkMode.CODE); // executable scenarios open in code mode
            ScenarioValidation v = ScenarioFixtures.JSON.readValue(s.getValidationJson(), ScenarioValidation.class);
            assertThat(v.starter().status()).isEqualTo("FAILED"); // the starter reproduces the problem...
            assertThat(v.starter().passed()).isEqualTo(2);
            assertThat(v.reference().status()).isEqualTo("PASSED"); // ...and the reference fixes it
            assertThat(v.reference().total()).isEqualTo(3);
        });

        // One plan over the whole context with the review's concerns; each build only sees its own files.
        List<AiPrompt> generation = generationPrompts();
        assertThat(generation).hasSize(6);
        AiPrompt plan = generation.get(0);
        assertThat(plan.user()).contains("CONCERNS FROM THIS REPOSITORY'S ENGINEERING REVIEW", "app = FastAPI()", "def place(order, store)");
        AiPrompt build = generation.get(1);
        assertThat(build.user()).contains("def place(order, store)").doesNotContain("app = FastAPI()");
        // Nothing about the developer reaches generation: scenarios depend on the code and the chosen target only.
        assertThat(generation).allSatisfy(p -> assertThat(p.system() + p.user()).doesNotContain("UNDERGRADUATE")
                .doesNotContain("Write cleaner, production-quality code").doesNotContain("Asha"));
        assertThat(plan.system()).contains("Seniority: SDE2").contains("Backend Engineer");
    }

    @Test
    void aDirectLabHasNoReviewHints() throws Exception {
        String auth = register("gen-direct@example.com");
        fakeGitHubRepository("asha", "direct");
        prompts.clear();
        String labId = startLab(auth, "\"repositoryUrl\":\"https://github.com/asha/direct\"");

        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth)).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(generationPrompts().get(0).user()).doesNotContain("CONCERNS FROM THIS REPOSITORY'S ENGINEERING REVIEW");
    }

    @Test
    void aHarnessThatDoesntReproduceTheProblemIsRepairedBeforeAnyoneSeesIt() throws Exception {
        String auth = register("gen-repair@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        AtomicInteger builds = new AtomicInteger();
        // The first scenario's starter already contains the fix, so its checks can't detect the problem.
        answers = p -> ScenarioFixtures.isBuild(p) && builds.incrementAndGet() == 1
                ? ScenarioFixtures.scenario(p, ScenarioFixtures.FIXED) : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");

        assertThat(labs.findById(UUID.fromString(labId)).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.ACTIVE);
        AiPrompt repair = generationPrompts().get(2);
        assertThat(repair.user()).contains("failed execution validation", "already passes every check");
        assertThat(scenarios.findByLabIdOrderByPositionAsc(UUID.fromString(labId)))
                .allSatisfy(s -> assertThat(s.getWorkspaceJson()).doesNotContain("# FIXED"));
    }

    @Test
    void codeScenariosThatCantBeProvenComeBackAsApproachOnlyRatherThanBroken() throws Exception {
        String auth = register("gen-fallback@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        // Every executable scenario the model writes is unprovable (its starter already passes).
        answers = p -> ScenarioFixtures.isBuild(p) && !ScenarioFixtures.isApproachBuild(p)
                ? ScenarioFixtures.scenario(p, ScenarioFixtures.FIXED) : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");

        List<Scenario> saved = scenarios.findByLabIdOrderByPositionAsc(UUID.fromString(labId));
        assertThat(saved).hasSize(5).allSatisfy(s -> {
            assertThat(s.getExecutionCapability()).isEqualTo(ExecutionCapability.APPROACH_ONLY);
            assertThat(s.getWorkspaceJson()).isNull();
            assertThat(s.getHarnessJson()).isNull();
            assertThat(s.getDraftMode()).isEqualTo(WorkMode.APPROACH);
        });
        // 1 plan, 7 code builds (5 + 2 spares) each with one repair, then 5 approach-only builds.
        assertThat(generationPrompts()).hasSize(1 + 7 * 2 + 5);
    }

    @Test
    void withoutASandboxEveryScenarioIsApproachOnly() throws Exception {
        String auth = register("gen-nosandbox@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        when(sandbox.available()).thenReturn(false);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");

        assertThat(generationPrompts().get(0).system()).contains("No code can be executed");
        assertThat(scenarios.findByLabIdOrderByPositionAsc(UUID.fromString(labId)))
                .hasSize(5).allSatisfy(s -> assertThat(s.getExecutionCapability()).isEqualTo(ExecutionCapability.APPROACH_ONLY));
    }

    @Test
    void outlinesCitingInventedFilesAreRejectedAndRepaired() throws Exception {
        String auth = register("gen-invented@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        AtomicInteger plans = new AtomicInteger();
        answers = p -> ScenarioFixtures.isPlan(p) && plans.incrementAndGet() == 1
                ? ScenarioFixtures.plan(p).replace("app/services/orders.py", "app/services/payments.py") : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");

        assertThat(labs.findById(UUID.fromString(labId)).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.ACTIVE);
        assertThat(generationPrompts().get(1).user()).contains("YOUR PREVIOUS ANSWER WAS REJECTED", "outlines are usable");
    }

    @Test
    void whenNoModelCanPlanTheLabFailsHonestlyAndTheUserCanStartAgain() throws Exception {
        String auth = register("gen-fail@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        answers = p -> ScenarioFixtures.isPlan(p) ? "this is not json" : defaultAnswer(p);

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");

        mvc.perform(get("/api/scenario-labs/" + labId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMessage").value("No AI model is available right now. Please try again in a few minutes."))
                .andExpect(jsonPath("$.scenarios.length()").value(0));
        mvc.perform(get("/api/scenario-labs/active").header("Authorization", auth)).andExpect(status().isNoContent());

        answers = ScenarioFlowSupport::defaultAnswer;
        String retry = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");
        assertThat(labs.findById(UUID.fromString(retry)).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.ACTIVE);
    }

    @Test
    void cancellingDuringGenerationStopsIt() throws Exception {
        String auth = register("gen-cancel@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        AtomicInteger builds = new AtomicInteger();
        answers = p -> {
            if (ScenarioFixtures.isBuild(p) && builds.incrementAndGet() == 2) {
                // The user cancels while the second scenario is being written.
                labs.findAll().stream().filter(l -> l.getStatus() == ScenarioLabStatus.GENERATING).forEach(l -> {
                    l.cancel();
                    labs.save(l);
                });
            }
            return defaultAnswer(p);
        };

        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");

        assertThat(labs.findById(UUID.fromString(labId)).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.CANCELLED);
        assertThat(scenarios.findByLabIdOrderByPositionAsc(UUID.fromString(labId))).hasSize(1);
        assertThat(builds.get()).isEqualTo(2); // nothing more was generated
    }

    @Test
    void generationInterruptedByARestartIsMarkedFailed() throws Exception {
        String auth = register("gen-restart@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String labId = startLab(auth, "\"repositoryId\":\"" + repoId + "\"");
        ScenarioLab done = labs.findById(UUID.fromString(labId)).orElseThrow();
        done.startFinalizing();
        done.complete();
        labs.save(done);
        ScenarioLab stuck = labs.save(new ScenarioLab(done.getUserId(), done.getRepositoryId(), null, done.getAnalysisRunId(), COMMIT,
                List.of(ScenarioRole.BACKEND_ENGINEER), Seniority.SDE1, 5));

        worker.failInterruptedGeneration();

        ScenarioLab after = labs.findById(stuck.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ScenarioLabStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERRUPTED");
        assertThat(after.getActiveUserId()).isNull();
        assertThat(labs.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(ScenarioLabStatus.COMPLETED); // untouched
    }
}
