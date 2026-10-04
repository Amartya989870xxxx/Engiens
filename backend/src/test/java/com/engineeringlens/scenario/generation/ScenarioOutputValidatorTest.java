package com.engineeringlens.scenario.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.ai.InvalidAiOutputException;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.review.LoadedContext;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;
import com.engineeringlens.scenario.generation.ScenarioContextBuilder.ScenarioContext;

import jakarta.validation.Validation;

/** Generation output is untrusted: roles, files, modes and code parts are checked against what really exists. */
class ScenarioOutputValidatorTest {

    private static final ScenarioOutputValidator VALIDATOR = new ScenarioOutputValidator(
            Validation.buildDefaultValidatorFactory().getValidator());

    private static final ScenarioContext CTX = new ScenarioContext(null, new LoadedContext(
            new AnalysisContext(1, null, null, null, Map.of("app/orders.py", "a\nb\nc\n", "deploy/main.tf", "x\n")),
            Set.of("app/orders.py", "deploy/main.tf", "README.md"), Map.of("app/orders.py", 3, "deploy/main.tf", 1), List.of(), null),
            List.of(), Set.of(ScenarioLanguage.PYTHON));

    private static ScenarioLab lab(ScenarioRole... roles) {
        return new ScenarioLab(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), "abc", List.of(roles), Seniority.SDE1, 1);
    }

    private static String outline(String key, String role, String mode, String language, String file) {
        return """
                {"key":"%s","title":"Title %s","role":"%s","category":"CONCURRENCY_CONSISTENCY","difficulty":"INTERMEDIATE",
                 "mode":"%s","language":%s,"problem":"p","groundedIn":[{"file":"%s","lineStart":2,"lineEnd":40,"why":"w"}],
                 "expectedConcepts":["c"],"whyItFitsTheLevel":"w"}""".formatted(key, key, role, mode,
                language == null ? "null" : "\"" + language + "\"", file);
    }

    private static String plan(String... outlines) {
        return "{\"scenarioPlanSchemaVersion\":1,\"repositorySummary\":\"s\",\"outlines\":[" + String.join(",", outlines) + "]}";
    }

    @Test
    void keepsOnlyOutlinesWithAllowedRolesAndRealFilesAndClampsLines() {
        ScenarioPlan plan = VALIDATOR.plan(plan(
                outline("S1", "BACKEND_ENGINEER", "CODE", "PYTHON", "./app/orders.py"),
                outline("S2", "FRONTEND_ENGINEER", "CODE", "PYTHON", "app/orders.py"),   // role not chosen
                outline("S3", "BACKEND_ENGINEER", "CODE", "PYTHON", "app/payments.py")), // invented file
                lab(ScenarioRole.BACKEND_ENGINEER), CTX, 1);

        assertThat(plan.outlines()).extracting(ScenarioPlan.Outline::key).containsExactly("S1");
        ScenarioPlan.Grounding g = plan.outlines().get(0).groundedIn().get(0);
        assertThat(g.file()).isEqualTo("app/orders.py");
        assertThat(g.lineEnd()).isEqualTo(3); // clamped to what the model was shown
    }

    @Test
    void codeInALanguageTheSandboxCantRunBecomesApproachOnly() {
        ScenarioPlan plan = VALIDATOR.plan(plan(outline("S1", "CLOUD_ENGINEER", "CODE", "JAVA", "deploy/main.tf")),
                lab(ScenarioRole.CLOUD_ENGINEER), CTX, 1);
        assertThat(plan.outlines().get(0).mode()).isEqualTo(ExecutionCapability.APPROACH_ONLY);
        assertThat(plan.outlines().get(0).language()).isNull();
    }

    @Test
    void broadEngineeringAllowsAnySpecificRoleAndTooFewUsableOutlinesIsRejected() {
        assertThat(VALIDATOR.plan(plan(outline("S1", "DEVOPS_ENGINEER", "APPROACH_ONLY", null, "deploy/main.tf")),
                lab(ScenarioRole.BROAD_ENGINEERING), CTX, 1).outlines()).hasSize(1);
        assertThatThrownBy(() -> VALIDATOR.plan(plan(outline("S1", "BACKEND_ENGINEER", "CODE", "PYTHON", "nope.py")),
                lab(ScenarioRole.BACKEND_ENGINEER), CTX, 1))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("Only 0 outlines are usable");
        assertThatThrownBy(() -> VALIDATOR.plan("not json", lab(ScenarioRole.BACKEND_ENGINEER), CTX, 1))
                .isInstanceOf(InvalidAiOutputException.class);
    }

    private static final ScenarioPlan.Outline CODE_OUTLINE = new ScenarioPlan.Outline("S1", "t", ScenarioRole.BACKEND_ENGINEER,
            com.engineeringlens.scenario.ScenarioCategory.ERROR_HANDLING, com.engineeringlens.scenario.ScenarioDifficulty.FOUNDATIONAL,
            ExecutionCapability.CODE, ScenarioLanguage.PYTHON, "p",
            List.of(new ScenarioPlan.Grounding("app/orders.py", 1, 2, "w")), List.of("c"), "w");

    private static String scenario(String workspace, String solution) {
        return """
                {"scenarioSchemaVersion":1,"title":"t","summary":"s","incident":"i","context":"c","task":"t",
                 "expectedBehaviour":[],"constraints":[],"evidence":[{"file":"invented.py","lineStart":1,"lineEnd":1,"explanation":"e"}],
                 "expectedConcepts":["c"],"rubric":[{"criterion":"a","whatGoodLooksLike":"b"},{"criterion":"c","whatGoodLooksLike":"d"}],
                 "referenceReasoning":"r","workspace":%s,
                 "checks":{"source":"from engiens import check","checkNames":["a","b"]},"referenceSolution":%s}""".formatted(workspace, solution);
    }

    @Test
    void aCodeScenarioMustBeCompleteAndOnlyChangeEditableFiles() {
        String workspace = """
                {"language":"PYTHON","files":[{"path":"orders.py","content":"x","editable":true},
                                              {"path":"store.py","content":"y","editable":false}]}""";
        GeneratedScenario ok = VALIDATOR.scenario(scenario(workspace,
                "{\"files\":[{\"path\":\"orders.py\",\"content\":\"z\"}],\"explanation\":\"e\"}"), CODE_OUTLINE, CTX);
        assertThat(ok.evidence()).extracting(GeneratedScenario.Evidence::file).containsExactly("app/orders.py"); // invented one replaced

        assertThatThrownBy(() -> VALIDATOR.scenario(scenario(workspace,
                "{\"files\":[{\"path\":\"store.py\",\"content\":\"z\"}],\"explanation\":\"e\"}"), CODE_OUTLINE, CTX))
                .hasMessageContaining("must be one of the editable workspace files");
        assertThatThrownBy(() -> VALIDATOR.scenario(scenario("null", "null"), CODE_OUTLINE, CTX)).hasMessageContaining("needs a workspace");
        assertThatThrownBy(() -> VALIDATOR.scenario(scenario(
                "{\"language\":\"PYTHON\",\"files\":[{\"path\":\"../x.py\",\"content\":\"x\",\"editable\":true}]}", "null"), CODE_OUTLINE, CTX))
                .hasMessageContaining("Invalid workspace");
        assertThatThrownBy(() -> VALIDATOR.scenario(scenario(
                "{\"language\":\"JAVA\",\"files\":[{\"path\":\"A.java\",\"content\":\"x\",\"editable\":true}]}", "null"), CODE_OUTLINE, CTX))
                .hasMessageContaining("workspace.language must be PYTHON");
    }
}
