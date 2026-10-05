package com.engineeringlens.scenario.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.InvalidAiOutputException;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextManifest;
import com.engineeringlens.analysis.context.ContextProperties;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.review.LoadedContext;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;
import com.engineeringlens.scenario.generation.ScenarioContextBuilder.ScenarioContext;
import com.engineeringlens.scenario.generation.ScenarioFit.Layer;

import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;

/**
 * The selected roles and seniority control what a lab contains, checked deterministically on the plan and on every
 * built scenario. Found in a real run: a Backend Engineer / SDE1 lab on Surge got React modal tasks labelled
 * "Backend Engineer", because only the role label was checked.
 */
class ScenarioFitTest {

    private static final GenerationProperties LIMITS = new GenerationProperties(3, 4, java.time.Duration.ofMinutes(10),
            java.time.Duration.ofSeconds(90), 10, 0.4, 1, 14);
    private static final ScenarioOutputValidator VALIDATOR = new ScenarioOutputValidator(
            Validation.buildDefaultValidatorFactory().getValidator(), LIMITS);

    // Paths as they appear in the real Surge repository, plus deployment and ML files for the other roles.
    private static final String API = "backend/app/api/auth.py";
    private static final String SERVICE = "backend/app/services/investigations.py";
    private static final String REPO = "backend/app/db/repository.py";
    private static final String MODAL = "frontend/src/components/auth/SignInModal.tsx";
    private static final String HOOK = "frontend/src/hooks/useAuth.ts";
    private static final String DOCKERFILE = "Dockerfile";
    private static final String CI = ".github/workflows/ci.yml";
    private static final String TRAIN = "ml/train.py";
    private static final List<String> FILES = List.of(API, SERVICE, REPO, MODAL, HOOK, DOCKERFILE, CI, TRAIN, "README.md");

    private static final ScenarioContext CTX = context(FILES, Set.of(ScenarioLanguage.PYTHON, ScenarioLanguage.TYPESCRIPT));

    private static ScenarioContext context(List<String> files, Set<ScenarioLanguage> executable) {
        Map<String, String> contents = new LinkedHashMap<>();
        Map<String, Integer> lines = new LinkedHashMap<>();
        files.forEach(f -> {
            contents.put(f, "line\n".repeat(20));
            lines.put(f, 20);
        });
        ContextManifest manifest = new ContextManifest(1, "c0ffee", true, ContextProperties.defaults(), null, List.of(), Map.of(), null);
        AnalysisContext analysis = new AnalysisContext(1, null, new DeterministicAnalysis(1, 1, List.of(), List.of()), manifest, contents);
        return new ScenarioContext(null, new LoadedContext(analysis, Set.copyOf(files), lines, List.of(), null), List.of(), executable);
    }

    private static ScenarioLab lab(Seniority seniority, int count, ScenarioRole... roles) {
        return new ScenarioLab(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), "c0ffee", List.of(roles), seniority, count);
    }

    private record O(String key, String role, String category, String difficulty, String mode, String file, List<String> also,
            List<String> applicableRoles) {
        O(String key, String role, String category, String difficulty, String mode, String file) {
            this(key, role, category, difficulty, mode, file, List.of(), null);
        }

        String json() {
            List<String> grounding = new ArrayList<>();
            grounding.add(file);
            grounding.addAll(also);
            String groundedIn = String.join(",", grounding.stream()
                    .map(f -> "{\"file\":\"" + f + "\",\"lineStart\":1,\"lineEnd\":5,\"why\":\"w\"}").toList());
            String language = mode.equals("CODE") ? (file.endsWith(".py") ? "\"PYTHON\"" : "\"TYPESCRIPT\"") : "null";
            String applicable = applicableRoles == null ? "null"
                    : "[" + String.join(",", applicableRoles.stream().map(r -> "\"" + r + "\"").toList()) + "]";
            return """
                    {"key":"%s","title":"Title %s","role":"%s","applicableRoles":%s,"category":"%s","difficulty":"%s","mode":"%s",
                     "language":%s,"problem":"p","groundedIn":[%s],"expectedConcepts":["c"],"whyItFitsTheLevel":"w"}"""
                    .formatted(key, key, role, applicable, category, difficulty, mode, language, groundedIn);
        }
    }

    private static String plan(O... outlines) {
        return "{\"scenarioPlanSchemaVersion\":1,\"repositorySummary\":\"s\",\"outlines\":["
                + String.join(",", java.util.Arrays.stream(outlines).map(O::json).toList()) + "]}";
    }

    private static List<String> kept(ScenarioLab lab, int needed, O... outlines) {
        return VALIDATOR.plan(plan(outlines), lab, CTX, needed).outlines().stream().map(ScenarioPlan.Outline::key).toList();
    }

    @Test
    void layersComeFromThePathNotFromWhatTheModelClaims() {
        assertThat(ScenarioFit.layer(API)).isEqualTo(Layer.BACKEND);
        assertThat(ScenarioFit.layer(SERVICE)).isEqualTo(Layer.BACKEND);
        assertThat(ScenarioFit.layer("app/services/orders.py")).isEqualTo(Layer.BACKEND); // a workspace path, prefix dropped
        assertThat(ScenarioFit.layer(MODAL)).isEqualTo(Layer.FRONTEND);
        assertThat(ScenarioFit.layer(HOOK)).isEqualTo(Layer.FRONTEND);
        assertThat(ScenarioFit.layer("frontend/src/app/App.tsx")).isEqualTo(Layer.FRONTEND);
        assertThat(ScenarioFit.layer("frontend/src/api/client.ts")).isEqualTo(Layer.FRONTEND); // outermost folder wins
        assertThat(ScenarioFit.layer("src/components/SignInModal.ts")).isEqualTo(Layer.FRONTEND);
        assertThat(ScenarioFit.layer(DOCKERFILE)).isEqualTo(Layer.INFRASTRUCTURE);
        assertThat(ScenarioFit.layer(CI)).isEqualTo(Layer.INFRASTRUCTURE);
        assertThat(ScenarioFit.layer("deploy/main.tf")).isEqualTo(Layer.INFRASTRUCTURE);
        assertThat(ScenarioFit.layer("docker-compose.yml")).isEqualTo(Layer.INFRASTRUCTURE);
        assertThat(ScenarioFit.layer(TRAIN)).isEqualTo(Layer.ML);
        assertThat(ScenarioFit.layer("README.md")).isEqualTo(Layer.UNKNOWN);
        assertThat(ScenarioFit.layer("src/utils/format.ts")).isEqualTo(Layer.UNKNOWN);
    }

    @Test
    void caseA_backendSde1KeepsBackendWorkAndDropsFrontendWorkEvenWhenLabelledBackend() {
        List<String> kept = kept(lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), 1,
                new O("S1", "BACKEND_ENGINEER", "CORRECTNESS_BUG", "FOUNDATIONAL", "CODE", API),
                new O("S2", "BACKEND_ENGINEER", "PRODUCTION_BUG", "FOUNDATIONAL", "CODE", MODAL), // the Surge defect
                new O("S3", "BACKEND_ENGINEER", "FRONTEND_CLIENT", "FOUNDATIONAL", "CODE", SERVICE), // off-role category
                new O("S4", "BACKEND_ENGINEER", "DATABASE_CORRECTNESS", "INTERMEDIATE", "CODE", REPO),
                new O("S5", "BACKEND_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "INTERMEDIATE", "APPROACH_ONLY", DOCKERFILE),
                new O("S6", "FRONTEND_ENGINEER", "FRONTEND_CLIENT", "FOUNDATIONAL", "CODE", HOOK)); // role not selected
        assertThat(kept).containsExactly("S1", "S4");
    }

    @Test
    void caseB_frontendSde1KeepsClientWorkOnly() {
        List<String> kept = kept(lab(Seniority.SDE1, 5, ScenarioRole.FRONTEND_ENGINEER), 1,
                new O("S1", "FRONTEND_ENGINEER", "FRONTEND_CLIENT", "INTERMEDIATE", "CODE", HOOK),
                new O("S2", "FRONTEND_ENGINEER", "CONCURRENCY_CONSISTENCY", "INTERMEDIATE", "CODE", MODAL),
                new O("S3", "FRONTEND_ENGINEER", "ERROR_HANDLING", "FOUNDATIONAL", "CODE", API), // server code
                new O("S4", "FRONTEND_ENGINEER", "DATABASE_PERFORMANCE", "INTERMEDIATE", "CODE", HOOK),
                new O("S5", "FRONTEND_ENGINEER", "NETWORK_BEHAVIOR", "INTERMEDIATE", "APPROACH_ONLY", HOOK),
                new O("S6", "FRONTEND_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "INTERMEDIATE", "APPROACH_ONLY", DOCKERFILE));
        assertThat(kept).containsExactly("S1", "S2");
    }

    @Test
    void caseC_devopsSde2KeepsDeploymentAndOperationsAtSde2Depth() {
        List<String> kept = kept(lab(Seniority.SDE2, 5, ScenarioRole.DEVOPS_ENGINEER), 1,
                new O("S1", "DEVOPS_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "ADVANCED", "APPROACH_ONLY", DOCKERFILE),
                new O("S2", "DEVOPS_ENGINEER", "PRODUCTION_READINESS", "INTERMEDIATE", "APPROACH_ONLY", CI),
                new O("S3", "DEVOPS_ENGINEER", "OBSERVABILITY_OPERATIONS", "INTERMEDIATE", "APPROACH_ONLY", SERVICE),
                new O("S4", "DEVOPS_ENGINEER", "FRONTEND_CLIENT", "INTERMEDIATE", "CODE", MODAL),
                new O("S5", "DEVOPS_ENGINEER", "PRODUCTION_READINESS", "INTERMEDIATE", "APPROACH_ONLY", MODAL),
                new O("S6", "DEVOPS_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "FOUNDATIONAL", "APPROACH_ONLY", DOCKERFILE)); // too shallow
        assertThat(kept).containsExactly("S1", "S2", "S3");
    }

    @Test
    void caseD_backendPlusDevopsMixesBothButStillNoFrontend() {
        List<String> kept = kept(lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER, ScenarioRole.DEVOPS_ENGINEER), 1,
                new O("S1", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "CODE", API),
                new O("S2", "DEVOPS_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "INTERMEDIATE", "APPROACH_ONLY", DOCKERFILE),
                new O("S3", "BACKEND_ENGINEER", "CACHING", "INTERMEDIATE", "CODE", SERVICE),
                new O("S4", "BACKEND_ENGINEER", "FRONTEND_CLIENT", "INTERMEDIATE", "CODE", MODAL),
                new O("S5", "DEVOPS_ENGINEER", "PRODUCTION_READINESS", "INTERMEDIATE", "APPROACH_ONLY", HOOK));
        assertThat(kept).containsExactly("S1", "S2", "S3");
    }

    @Test
    void caseE_allRolesGivesBroadCoverageButEachScenarioMustFitTheRoleItNames() {
        List<String> kept = kept(lab(Seniority.SDE1, 5, ScenarioRole.BROAD_ENGINEERING), 1,
                new O("S1", "BACKEND_ENGINEER", "DATABASE_CORRECTNESS", "INTERMEDIATE", "CODE", REPO),
                new O("S2", "FRONTEND_ENGINEER", "FRONTEND_CLIENT", "INTERMEDIATE", "CODE", HOOK),
                new O("S3", "DEVOPS_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "INTERMEDIATE", "APPROACH_ONLY", CI),
                new O("S4", "ML_ENGINEER", "AI_ML_ENGINEERING", "INTERMEDIATE", "CODE", TRAIN),
                new O("S5", "BACKEND_ENGINEER", "CORRECTNESS_BUG", "INTERMEDIATE", "CODE", MODAL), // React code is not backend
                new O("S6", "BROAD_ENGINEERING", "CORRECTNESS_BUG", "INTERMEDIATE", "CODE", API));
        assertThat(kept).containsExactly("S1", "S2", "S3", "S4");
    }

    @Test
    void caseF_architectAtSde3AndSeniorArchitectGetSystemLevelWorkOnly() {
        assertThat(kept(lab(Seniority.SDE3, 5, ScenarioRole.SOFTWARE_ARCHITECT), 1,
                new O("S1", "SOFTWARE_ARCHITECT", "ARCHITECTURE_REFACTORING", "ADVANCED", "APPROACH_ONLY", SERVICE),
                new O("S2", "SOFTWARE_ARCHITECT", "SCALABILITY", "EXPERT", "APPROACH_ONLY", REPO),
                new O("S3", "SOFTWARE_ARCHITECT", "CORRECTNESS_BUG", "ADVANCED", "CODE", API), // a local bug fix
                new O("S4", "SOFTWARE_ARCHITECT", "TESTING_GAP", "ADVANCED", "APPROACH_ONLY", API),
                new O("S5", "SOFTWARE_ARCHITECT", "ARCHITECTURE_REFACTORING", "INTERMEDIATE", "APPROACH_ONLY", API))) // too shallow
                .containsExactly("S1", "S2");

        // Senior Architect seniority on a backend lab: decisions about boundaries and resilience, not bug fixes.
        assertThat(kept(lab(Seniority.SENIOR_ARCHITECT, 5, ScenarioRole.BACKEND_ENGINEER), 1,
                new O("S1", "BACKEND_ENGINEER", "CONCURRENCY_CONSISTENCY", "EXPERT", "APPROACH_ONLY", SERVICE),
                new O("S2", "BACKEND_ENGINEER", "ERROR_HANDLING", "ADVANCED", "CODE", API),
                new O("S3", "BACKEND_ENGINEER", "PRODUCTION_BUG", "EXPERT", "CODE", API)))
                .containsExactly("S1");
    }

    @Test
    void beginnerLabsStayFoundational() {
        assertThat(kept(lab(Seniority.BEGINNER, 5, ScenarioRole.BACKEND_ENGINEER), 1,
                new O("S1", "BACKEND_ENGINEER", "ERROR_HANDLING", "FOUNDATIONAL", "CODE", API),
                new O("S2", "BACKEND_ENGINEER", "SCALABILITY", "FOUNDATIONAL", "CODE", SERVICE),
                new O("S3", "BACKEND_ENGINEER", "CONCURRENCY_CONSISTENCY", "FOUNDATIONAL", "CODE", SERVICE),
                new O("S4", "BACKEND_ENGINEER", "TESTING_GAP", "INTERMEDIATE", "CODE", REPO)))
                .containsExactly("S1");
    }

    @Test
    void aCrossRoleScenarioIsValidOnlyWhenEveryRoleItInvolvesWasSelected() {
        O contract = new O("S1", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "CODE", API, List.of(HOOK),
                List.of("FRONTEND_ENGINEER"));
        O react = new O("S2", "FRONTEND_ENGINEER", "FRONTEND_CLIENT", "INTERMEDIATE", "CODE", MODAL);

        // Backend + Frontend: the client/server contract keeps its client file, and React work is valid.
        ScenarioPlan both = VALIDATOR.plan(plan(contract, react), lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER,
                ScenarioRole.FRONTEND_ENGINEER), CTX, 2);
        assertThat(both.outlines()).extracting(ScenarioPlan.Outline::key).containsExactly("S1", "S2");
        assertThat(both.outlines().get(0).groundedIn()).extracting(ScenarioPlan.Grounding::file).containsExactly(API, HOOK);

        // Backend only: naming an unselected role is a misfit; a plain backend scenario just loses its client-side file.
        assertThatThrownBy(() -> VALIDATOR.plan(plan(contract, react), lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), CTX, 1))
                .isInstanceOf(InvalidAiOutputException.class)
                .hasMessageContaining("applicableRoles may only name selected roles")
                .hasMessageContaining("role FRONTEND_ENGINEER was not selected");
        O backendCitingClient = new O("S3", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "CODE", API, List.of(HOOK, SERVICE), null);
        ScenarioPlan backend = VALIDATOR.plan(plan(backendCitingClient), lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), CTX, 1);
        assertThat(backend.outlines().get(0).groundedIn()).extracting(ScenarioPlan.Grounding::file).containsExactly(API, SERVICE);
    }

    @Test
    void codeRolesMustGetMostlyExecutableScenariosButOperationsRolesNeedNot() {
        O[] mostlyApproach = {
                new O("S1", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "APPROACH_ONLY", API),
                new O("S2", "BACKEND_ENGINEER", "CACHING", "INTERMEDIATE", "APPROACH_ONLY", SERVICE),
                new O("S3", "BACKEND_ENGINEER", "DATABASE_CORRECTNESS", "INTERMEDIATE", "CODE", REPO) };
        assertThatThrownBy(() -> VALIDATOR.plan(plan(mostlyApproach), lab(Seniority.SDE1, 3, ScenarioRole.BACKEND_ENGINEER), CTX, 3))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("at least 2 must be");

        assertThat(kept(lab(Seniority.SDE2, 3, ScenarioRole.DEVOPS_ENGINEER), 2,
                new O("S1", "DEVOPS_ENGINEER", "INFRASTRUCTURE_DEPLOYMENT", "ADVANCED", "APPROACH_ONLY", DOCKERFILE),
                new O("S2", "DEVOPS_ENGINEER", "PRODUCTION_READINESS", "ADVANCED", "APPROACH_ONLY", CI))).hasSize(2);

        // Nothing executable in this repository: approach-only is the only option, so it isn't required.
        ScenarioContext noSandbox = context(FILES, Set.of());
        assertThat(VALIDATOR.plan(plan(mostlyApproach), lab(Seniority.SDE1, 3, ScenarioRole.BACKEND_ENGINEER), noSandbox, 3)
                .outlines()).hasSize(3);
    }

    @Test
    void roleFitOutranksDiversityInLargeLabs() {
        // Backend has plenty of categories: the normal 20-scenario cap of 5 per category applies.
        assertThat(VALIDATOR.diversityCap(lab(Seniority.SDE1, 20, ScenarioRole.BACKEND_ENGINEER))).isEqualTo(5);
        // An AI researcher beginner lab has only 3 categories: the cap widens rather than pulling in off-role ones.
        assertThat(ScenarioFit.categories(lab(Seniority.BEGINNER, 20, ScenarioRole.AI_RESEARCHER))).hasSize(3);
        assertThat(VALIDATOR.diversityCap(lab(Seniority.BEGINNER, 20, ScenarioRole.AI_RESEARCHER))).isEqualTo(7);
        assertThat(VALIDATOR.diversityCap(lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER))).isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * Seen in a real 20-scenario run: the second plan batch reworded outlines from the first (two body-size middleware
     * scenarios on main.py, two UTCDateTime scenarios on models.py). In a large lab the same kind of problem in the
     * same file appears once, across batches too; small labs are not held to it.
     */
    @Test
    void aLargeLabHasNoTwoOutlinesOfTheSameKindInTheSameFile() {
        ScenarioLab twenty = lab(Seniority.SDE1, 20, ScenarioRole.BACKEND_ENGINEER);
        O first = new O("S1", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "CODE", API);
        O reworded = new O("S2", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "CODE", API);
        O otherKind = new O("S3", "BACKEND_ENGINEER", "ERROR_HANDLING", "INTERMEDIATE", "CODE", API);
        assertThat(kept(twenty, 1, first, reworded, otherKind)).containsExactly("S1", "S3");

        List<ScenarioPlan.Outline> earlierBatch = VALIDATOR.plan(plan(first), twenty, CTX, 1).outlines();
        assertThat(VALIDATOR.plan(plan(reworded, otherKind), twenty, CTX, 1, earlierBatch).outlines())
                .extracting(ScenarioPlan.Outline::key).containsExactly("S3");

        assertThat(kept(lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), 1, first, reworded)).containsExactly("S1", "S2");
    }

    @Test
    void aBuiltScenarioWhoseWorkspaceIsOffRoleIsRejectedBeforeItCanBePublished() {
        ScenarioPlan.Outline backend = VALIDATOR.plan(plan(new O("S1", "BACKEND_ENGINEER", "API_RELIABILITY", "INTERMEDIATE", "CODE",
                API)), lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), CTX, 1).outlines().get(0);
        String frontendWorkspace = scenario("src/components/SignInModal.py");
        assertThatThrownBy(() -> VALIDATOR.scenario(frontendWorkspace, backend, CTX))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("frontend code");
        assertThat(VALIDATOR.scenario(scenario("app/api/auth.py"), backend, CTX).workspace().files()).hasSize(1);
    }

    private static String scenario(String editablePath) {
        return """
                {"scenarioSchemaVersion":1,"title":"t","summary":"s","incident":"i","context":"c","task":"t",
                 "expectedBehaviour":[],"constraints":[],"evidence":[],"expectedConcepts":["c"],
                 "rubric":[{"criterion":"a","whatGoodLooksLike":"b"},{"criterion":"c","whatGoodLooksLike":"d"}],
                 "referenceReasoning":"r",
                 "workspace":{"language":"PYTHON","files":[{"path":"%1$s","content":"x","editable":true}]},
                 "checks":{"source":"from engiens import check","checkNames":["a","b"]},
                 "referenceSolution":{"files":[{"path":"%1$s","content":"y"}],"explanation":"e"}}""".formatted(editablePath);
    }

    @Test
    void aRepositoryWithNoCodeForTheChosenRolesIsDetectedBeforeAnyModelCall() {
        List<String> backendOnly = List.of(API, SERVICE, "README.md", "pyproject.toml");
        assertThat(ScenarioFit.hasCodeFor(lab(Seniority.SDE1, 5, ScenarioRole.FRONTEND_ENGINEER), backendOnly)).isFalse();
        assertThat(ScenarioFit.hasCodeFor(lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), backendOnly)).isTrue();
        assertThat(ScenarioFit.hasCodeFor(lab(Seniority.SDE1, 5, ScenarioRole.BROAD_ENGINEERING), backendOnly)).isTrue();
        assertThat(ScenarioFit.hasCodeFor(lab(Seniority.SDE1, 5, ScenarioRole.BACKEND_ENGINEER), List.of("README.md"))).isFalse();
    }

    @Test
    void thePlanPromptOffersOnlyTheCategoriesAndDifficultiesThatFit() {
        ScenarioPromptBuilder prompts = new ScenarioPromptBuilder(new ObjectMapper(), VALIDATOR);
        AiPrompt backend = prompts.plan(CTX, lab(Seniority.SDE1, 20, ScenarioRole.BACKEND_ENGINEER), 28);
        assertThat(backend.system()).contains("ROLE = \"BACKEND_ENGINEER\"")
                .contains("DIFFICULTY = \"FOUNDATIONAL\" | \"INTERMEDIATE\"")
                .contains("at least three quarters of the outlines must be CODE")
                .contains("never a UI component")
                .contains("at most 5 outlines may share a category");
        String categories = backend.system().lines().filter(l -> l.startsWith("CATEGORY = ")).findFirst().orElseThrow();
        assertThat(categories).contains("\"API_RELIABILITY\"", "\"DATABASE_CORRECTNESS\"")
                .doesNotContain("FRONTEND_CLIENT", "INFRASTRUCTURE_DEPLOYMENT", "AI_ML_ENGINEERING");

        AiPrompt architect = prompts.plan(CTX, lab(Seniority.SENIOR_ARCHITECT, 5, ScenarioRole.SOFTWARE_ARCHITECT), 7);
        assertThat(architect.system()).contains("DIFFICULTY = \"ADVANCED\" | \"EXPERT\"").doesNotContain("three quarters");
    }
}
