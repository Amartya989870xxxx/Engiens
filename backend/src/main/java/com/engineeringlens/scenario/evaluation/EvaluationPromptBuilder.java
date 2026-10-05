package com.engineeringlens.scenario.evaluation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.review.ReviewPromptBuilder;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioAttempt;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.WorkMode;
import com.engineeringlens.scenario.execution.CheckResult;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.model.ScenarioDocument;
import com.engineeringlens.scenario.model.ScenarioEvaluation;
import com.engineeringlens.scenario.model.ScenarioReference;
import com.engineeringlens.scenario.model.ScenarioWorkspace;
import com.engineeringlens.scenario.workspace.FileContent;

import tools.jackson.databind.ObjectMapper;

/**
 * The three evaluation prompts, following the review's principle: assess first, teach second.
 * <ol>
 * <li><b>Assess</b> one submission: scenario, code, check results, reasoning, target level. Nothing about the developer.</li>
 * <li><b>Summarise</b> the lab: the verdicts and the target level. Still nothing about the developer.</li>
 * <li><b>Teach</b>: the developer's profile and the final verdicts, no code. It can only add learning points.</li>
 * </ol>
 */
@Component
public class EvaluationPromptBuilder {

    private final ObjectMapper json;

    public EvaluationPromptBuilder(ObjectMapper json) {
        this.json = json;
    }

    /** One scored-in-words evaluation of one submitted scenario. Contains code: never logged or stored. */
    public AiPrompt assess(ScenarioLab lab, Scenario s, ScenarioDocument doc, ScenarioReference reference, ScenarioWorkspace workspace,
            ScenarioAttempt attempt, List<FileContent> submitted, RunResult run) {
        String system = """
                # ROLE
                You are a senior software engineer evaluating how a developer solved an engineering scenario taken from their
                own repository. You evaluate the thought process and the engineering decisions, not just the final answer.

                # PRINCIPLES
                1. The reference is ONE valid approach. A different approach that correctly solves the problem with sound
                   trade-offs is equally good. Never penalise a submission for differing from the reference.
                2. Check results are objective evidence, not the grade. Passing checks don't prove the design is sound: look
                   for races, failure modes and regressions the checks don't cover. Failing checks are real defects unless
                   the check is clearly unfair, in which case say so.
                3. In CODE mode the submitted code is the primary evidence and the written approach explains it. In APPROACH
                   mode the written reasoning is the primary evidence; code is secondary.
                4. Judge against the scenario's target level: %s (%s). You are deliberately not told who the developer is:
                   apply the same bar to everyone.
                5. Be specific: point at the submitted code or reasoning. Never claim the submission does something it doesn't.
                6. If nothing meaningful was submitted (starter code unchanged and no real reasoning), the verdict is
                   NOT_ASSESSABLE and the assessment says so.
                7. No numeric scores, no claims of "industry readiness" or of any company's internal standards.
                8. Plain text inside strings. Short code is fine in recommendedFix and referenceApproach.

                # VERDICT
                STRONG: correct, well reasoned, sound trade-offs for the target level. SOLID: correct with minor gaps.
                DEVELOPING: partly correct, or important gaps in reasoning. NEEDS_ATTENTION: incorrect, or misses the root cause.
                NOT_ASSESSABLE: nothing to judge.

                # OUTPUT CONTRACT
                Return exactly one JSON object:
                {"scenarioEvaluationSchemaVersion": %d, "verdict": "STRONG"|"SOLID"|"DEVELOPING"|"NEEDS_ATTENTION"|"NOT_ASSESSABLE",
                 "confidence": "HIGH"|"MEDIUM"|"LOW", "assessment": str (2-4 sentences),
                 "whatWasCorrect": [str], "whatWasMissed": [str], "rootCause": str (the real cause of the problem),
                 "engineeringJudgment": str (quality of the decisions and reasoning), "tradeoffs": [str],
                 "scaleImpact": str (what happens to this solution at larger scale), "regressionRisk": str,
                 "testingAssessment": str (which tests should exist; what the check results do and don't show),
                 "recommendedFix": str (how to improve THIS submission), "referenceApproach": str (how a stronger solution works)}
                """.formatted(lab.getSeniority().label(), s.getRole().label(), ScenarioEvaluation.SCHEMA_VERSION);

        StringBuilder u = new StringBuilder();
        u.append("# SCENARIO (as the developer saw it)\n").append(json.writeValueAsString(doc)).append('\n');
        u.append("category: ").append(s.getCategory()).append(", difficulty: ").append(s.getDifficulty()).append('\n');
        u.append("\n# WHAT A STRONG ANSWER COVERS (for you only)\nexpected concepts: ").append(reference.expectedConcepts()).append('\n');
        reference.rubric().forEach(c -> u.append("- ").append(c.criterion()).append(": ").append(c.whatGoodLooksLike()).append('\n'));
        u.append("reference reasoning (one valid approach): ").append(reference.referenceReasoning()).append('\n');
        if (reference.solution() != null) {
            u.append("\n# REFERENCE SOLUTION (one valid approach; do not require matching it)\n");
            reference.solution().files().forEach(f -> u.append("===== ").append(f.path()).append(" =====\n").append(f.content()).append('\n'));
        }
        if (workspace != null) {
            u.append("\n# STARTER WORKSPACE (what the developer was given)\n");
            workspace.files().forEach(f -> u.append("===== ").append(f.path()).append(f.editable() ? "" : " (read-only)").append(" =====\n")
                    .append(ReviewPromptBuilder.numbered(f.content())));
        }
        u.append("\n# SUBMISSION\nmode: ").append(attempt.getMode())
                .append(attempt.getMode() == WorkMode.CODE ? " (code is the primary evidence)" : " (reasoning is the primary evidence)").append('\n');
        if (submitted != null && !submitted.isEmpty()) {
            u.append("\n## Submitted files (full content of each editable file)\n");
            for (FileContent f : submitted) {
                String starter = workspace == null ? null
                        : workspace.files().stream().filter(w -> w.path().equals(f.path())).map(ScenarioWorkspace.File::content).findFirst().orElse(null);
                u.append("===== ").append(f.path()).append(f.content().equals(starter) ? " (UNCHANGED from the starter)" : " (changed)")
                        .append(" =====\n").append(ReviewPromptBuilder.numbered(f.content()));
            }
        }
        u.append("\n## Check results (objective, from running the hidden checks in a sandbox)\n");
        if (run == null) {
            u.append(workspace == null ? "Not applicable: an approach-only scenario.\n" : "Not available: the sandbox couldn't run this submission.\n");
        } else {
            u.append("status: ").append(run.status()).append(", passed ").append(run.passed()).append(" of ").append(run.total()).append('\n');
            if (run.message() != null) {
                u.append("message: ").append(run.message()).append('\n');
            }
            for (CheckResult c : run.checks()) {
                u.append(c.passed() ? "PASS " : "FAIL ").append(c.name()).append(c.message() == null ? "" : ": " + c.message()).append('\n');
            }
            if (run.stderr() != null && !run.stderr().isBlank()) {
                u.append("stderr (start): ").append(cap(run.stderr(), 2000)).append('\n');
            }
        }
        u.append("\n## Written approach\n").append(attempt.getSubmittedApproach() == null ? "(none)" : attempt.getSubmittedApproach()).append('\n');
        return new AiPrompt(system, u.toString());
    }

    /** The lab's overall picture from the verdicts alone. No code, no developer. */
    public AiPrompt summarise(ScenarioLab lab, List<EvaluatedScenario> evaluated) {
        String system = """
                # ROLE
                You are a senior engineer writing the overall assessment of an engineering scenario lab. Each scenario has
                already been evaluated; those verdicts are final.

                # RULES
                1. Base everything on the evaluations below. Never change or contradict a verdict.
                2. Describe the developer's engineering against the lab's target (%s, roles %s): where they meet it, where
                   they don't. engineeringLevel is a sentence, never a score, a percentage or a job title promise.
                3. You are deliberately not told who the developer is.
                4. No claims of "industry readiness" or of any company's internal standards. Plain text only.

                # OUTPUT CONTRACT
                {"labSummarySchemaVersion": 1,
                 "overallAssessment": {"summary": str (3-5 sentences), "engineeringLevel": str, "confidence": "HIGH"|"MEDIUM"|"LOW"},
                 "strengths": [str], "growthAreas": [str], "limitations": [str]}
                """.formatted(lab.getSeniority().label(), lab.getRoles().stream().map(r -> r.label()).toList());
        String scope = evaluated.size() < lab.getScenarioCount()
                ? "The developer finished the lab early: " + evaluated.size() + " of " + lab.getScenarioCount()
                        + " scenarios were submitted. Assess only these, and treat the small sample as a limitation.\n\n"
                : "";
        return new AiPrompt(system, scope + "# EVALUATED SCENARIOS\n" + json.writeValueAsString(evaluated) + "\n");
    }

    /** Learning points for THIS developer, from the final verdicts. No code; no way to change a verdict. */
    public AiPrompt teach(String audienceGuidance, String profileJson, String audience, Object summary, List<EvaluatedScenario> evaluated) {
        String system = """
                # ROLE
                You are a senior engineer mentoring the developer described below, after their scenario lab was assessed.
                The assessment is final.

                # TASK
                For each scenario, 1 to 3 short learning points for THIS developer, and up to 5 learning recommendations
                connected to their project.

                # RULES
                1. Never change, restate or contradict a verdict. You only teach.
                2. Use only what the evaluations say. Don't invent problems.
                3. Never claim knowledge of any company's internal standards. Plain text only.

                # PERSONALISATION
                """ + audienceGuidance + """


                # OUTPUT CONTRACT
                {"scenarioLearning": [{"scenarioId": str (exactly as given), "learningPoints": [str]}],
                 "learningRecommendations": [{"topic": str, "why": str, "connectionToProject": str}]}
                """;
        return new AiPrompt(system, "# DEVELOPER PROFILE\naudience: " + audience + "\n" + profileJson + "\n\n# LAB SUMMARY (final)\n"
                + json.writeValueAsString(summary) + "\n\n# EVALUATED SCENARIOS (final)\n" + json.writeValueAsString(evaluated) + "\n");
    }

    public AiPrompt repair(AiPrompt original, String problem) {
        return new AiPrompt(original.system(), original.user() + "\n\n# YOUR PREVIOUS ANSWER WAS REJECTED\n" + problem + "\n"
                + "Return ONLY one complete JSON object following the OUTPUT CONTRACT.");
    }

    /** What the summary and teaching steps see of each scenario: titles and verdicts, never code. */
    public record EvaluatedScenario(String scenarioId, String title, String category, String mode, String checks, ScenarioEvaluation evaluation) {
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
