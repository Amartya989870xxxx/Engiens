package com.engineeringlens.scenario.generation;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextFile;
import com.engineeringlens.analysis.deterministic.Signal;
import com.engineeringlens.analysis.review.ReviewPromptBuilder;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioDifficulty;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.generation.ScenarioContextBuilder.ScenarioContext;

import tools.jackson.databind.ObjectMapper;

/**
 * The two generation prompts. Neither contains anything about the developer: scenarios are defined by the
 * repository, the chosen roles and the chosen seniority, so the same choices on the same code give the
 * same kind of lab to everyone. Prompts contain source code, so they are never logged or stored.
 */
@Component
public class ScenarioPromptBuilder {

    private static final int MAX_FILES_PER_SCENARIO = 6;

    private final ObjectMapper json;
    private final ScenarioOutputValidator validator;

    public ScenarioPromptBuilder(ObjectMapper json, ScenarioOutputValidator validator) {
        this.json = json;
        this.validator = validator;
    }

    // ---- step 1: plan ---------------------------------------------------------------------------

    public AiPrompt plan(ScenarioContext ctx, ScenarioLab lab, int outlines) {
        StringBuilder s = new StringBuilder(RULES);
        s.append("\n# TASK\nPropose exactly ").append(outlines).append(" scenario outlines for this repository (")
                .append(lab.getScenarioCount()).append(" will be used; the rest are spares, so make every one usable).\n")
                .append("Vary the categories and the parts of the code they touch. Order them from most to least valuable.\n");
        int cap = validator.diversityCap(lab.getScenarioCount());
        if (cap != Integer.MAX_VALUE) {
            s.append("This is a large lab: at most ").append(cap).append(" outlines may share a category, and at most ").append(cap)
                    .append(" may have the same first groundedIn file. Spread them across different parts of the repository.\n");
        }
        s.append(audience(lab));
        s.append("\n# EXECUTION\n");
        if (ctx.executableLanguages().isEmpty()) {
            s.append("No code can be executed for this lab: every outline must use mode APPROACH_ONLY with language null.\n");
        } else {
            s.append("CODE scenarios can be executed in: ").append(ctx.executableLanguages()).append(
                    ". Prefer mode CODE where an executable fix is natural (backend, frontend logic, full-stack, AI/ML code) and the "
                            + "grounding files are in one of those languages; set language to that language. Use APPROACH_ONLY "
                            + "(language null) for architecture, cloud, network, infrastructure or research decisions, or when the "
                            + "relevant code isn't in an executable language. When the selected roles allow it, at least half the "
                            + "outlines should be CODE.\n");
        }
        s.append("\n# OUTPUT CONTRACT\n").append("""
                Return exactly one JSON object:
                {
                  "scenarioPlanSchemaVersion": %d,
                  "repositorySummary": str (2-3 sentences: what this system is and how it is built),
                  "outlines": [ {
                    "key": "S1", "S2", ...,
                    "title": str (specific, e.g. "Prevent duplicate orders under concurrent checkout"),
                    "role": ROLE, "category": CATEGORY, "difficulty": DIFFICULTY,
                    "mode": "CODE" | "APPROACH_ONLY", "language": LANGUAGE | null,
                    "problem": str (2-4 sentences: what goes wrong or what is needed, in this repository's terms),
                    "groundedIn": [ {"file": exact path from the inputs, "lineStart": int|null, "lineEnd": int|null,
                                     "why": str} ]  (1 to 4, the code the scenario is about),
                    "expectedConcepts": [str],
                    "whyItFitsTheLevel": str
                  } ]
                }
                ROLE = %s
                CATEGORY = %s
                DIFFICULTY = %s
                LANGUAGE = %s
                """.formatted(ScenarioPlan.SCHEMA_VERSION, allowedRoles(lab), names(ScenarioCategory.values()),
                names(ScenarioDifficulty.values()), ctx.executableLanguages().isEmpty() ? "null" : names(ctx.executableLanguages().toArray())));
        return new AiPrompt(s.toString(), evidence(ctx, null));
    }

    // ---- step 2: build one scenario -------------------------------------------------------------

    public AiPrompt build(ScenarioContext ctx, ScenarioLab lab, ScenarioPlan.Outline outline) {
        boolean code = outline.mode() == ExecutionCapability.CODE;
        StringBuilder s = new StringBuilder(RULES);
        s.append("\n# TASK\nTurn the outline below into a complete scenario a developer can work on. It must be about THIS ")
                .append("repository: describe the incident and context in its terms, using its files, functions and data.\n");
        s.append(audience(lab));
        s.append("\n# OUTLINE\n").append(json.writeValueAsString(outline)).append('\n');
        if (code) {
            s.append("\n# EXECUTABLE WORKSPACE\n").append(ScenarioGuidance.languageContract(outline.language()));
        } else {
            s.append("""

                    # APPROACH-ONLY SCENARIO
                    The developer answers in writing (diagnosis, root cause, proposed change, trade-offs, testing, scale). Set
                    "workspace", "checks" and "referenceSolution" to null. Make referenceReasoning a thorough model answer.
                    """);
        }
        s.append("\n# OUTPUT CONTRACT\n").append("""
                Return exactly one JSON object:
                {
                  "scenarioSchemaVersion": 1,
                  "title": str, "summary": str (1-2 sentences),
                  "incident": str (what is happening: symptoms, impact; realistic, specific to this system),
                  "context": str (the relevant architecture of this repository),
                  "task": str (what the developer must do; for CODE, what to change in the workspace),
                  "expectedBehaviour": [str], "constraints": [str],
                  "evidence": [ {"file": exact path from the inputs, "lineStart": int|null, "lineEnd": int|null, "explanation": str} ],
                  "expectedConcepts": [str],
                  "rubric": [ {"criterion": str, "whatGoodLooksLike": str} ] (3 to 6),
                  "referenceReasoning": str (how a strong engineer would diagnose and solve it, with trade-offs),
                """);
        if (code) {
            s.append("""
                      "workspace": {"language": "%s", "files": [ {"path": str, "content": str, "editable": bool} ]},
                      "checks": {"source": str (the whole checks file), "checkNames": [str]},
                      "referenceSolution": {"files": [ {"path": str, "content": str} ], "explanation": str}
                    }
                    """.formatted(outline.language()));
        } else {
            s.append("  \"workspace\": null, \"checks\": null, \"referenceSolution\": null\n}\n");
        }
        return new AiPrompt(s.toString(), evidence(ctx, files(outline)));
    }

    /** Models are stateless: the repair attempt resends everything plus the exact reason it was rejected. */
    public AiPrompt repair(AiPrompt original, String problem) {
        return new AiPrompt(original.system(), original.user() + "\n\n# YOUR PREVIOUS ANSWER WAS REJECTED\n" + problem + "\n"
                + "Fix exactly that and return ONLY one complete JSON object following the OUTPUT CONTRACT.");
    }

    // ---- shared ---------------------------------------------------------------------------------

    static final String RULES = """
            # ROLE
            You design realistic engineering assessment scenarios from a developer's real repository. A scenario is a
            production-style problem in THIS system (a bug, an incident, a missing safeguard, a scaling limit, a design
            decision) that tests engineering judgement. It is not a coding puzzle, trivia or a generic interview question.

            # RULES
            1. Use repository evidence. Every scenario must be about code you were shown; cite exact paths from the inputs.
            2. Do not invent files, endpoints, services, infrastructure or behaviour the inputs don't support. If the repository
               has no database, don't write a database scenario; if it has no deployment config, don't assume one.
            3. Scenarios are hypothetical incidents grounded in the code ("under concurrent requests this could create..."):
               don't claim something has happened or will certainly happen. Be honest when evidence is thin.
            4. Never claim knowledge of any company's internal standards or "how FAANG does it".
            5. No generic exercises (reverse a linked list, design Twitter) unless the repository itself directly justifies it.
            6. Scenarios must be solvable, specific and non-trivial for the target level, and must not be trick questions.
            7. You are deliberately not told who will take this lab: target the chosen roles and seniority only.
            8. Plain text inside strings (code only inside workspace/checks/solution contents). Return only the JSON object.
            """;

    private String audience(ScenarioLab lab) {
        StringBuilder s = new StringBuilder("\n# TARGET\nRoles:\n");
        for (ScenarioRole role : lab.getRoles()) {
            s.append("- ").append(role.label()).append(": ").append(ScenarioGuidance.ROLE_FOCUS.get(role)).append('\n');
        }
        s.append("Seniority: ").append(lab.getSeniority().label()).append(" - ").append(ScenarioGuidance.SENIORITY_DEPTH.get(lab.getSeniority()))
                .append("\nHigher seniority changes the engineering depth of the problem, not the length of the text.\n");
        return s.toString();
    }

    /** Broad Engineering lets the generator pick any specific role; otherwise only the chosen ones. */
    private static String allowedRoles(ScenarioLab lab) {
        List<ScenarioRole> roles = lab.getRoles().contains(ScenarioRole.BROAD_ENGINEERING)
                ? Arrays.stream(ScenarioRole.values()).filter(r -> r != ScenarioRole.BROAD_ENGINEERING).toList()
                : lab.getRoles();
        return names(roles.toArray());
    }

    private static Set<String> files(ScenarioPlan.Outline outline) {
        return outline.groundedIn().stream().map(ScenarioPlan.Grounding::file).limit(MAX_FILES_PER_SCENARIO)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The repository evidence. {@code onlyFiles} null = everything selected (the plan); otherwise just those files
     * (one scenario), so each build call carries only what it needs.
     */
    String evidence(ScenarioContext ctx, Set<String> onlyFiles) {
        AnalysisContext c = ctx.loaded().context();
        StringBuilder u = new StringBuilder();
        u.append("# REPOSITORY PROFILE\n").append(json.writeValueAsString(c.profile())).append("\n\n");
        u.append("# DETERMINISTIC SIGNALS (facts Engiens verified)\n");
        for (Signal sig : c.analysis().signals()) {
            u.append("- ").append(sig.ruleId()).append(": ").append(sig.message())
                    .append(sig.file() == null ? "" : " (" + sig.file() + ")").append('\n');
        }
        if (!ctx.hints().isEmpty()) {
            u.append("\n# CONCERNS FROM THIS REPOSITORY'S ENGINEERING REVIEW (good starting points for scenarios)\n");
            ctx.hints().forEach(h -> u.append("- ").append(h).append('\n'));
        }
        u.append("\n# FILE CONTENTS (line numbers before each line)\n");
        for (Map.Entry<String, String> e : c.contents().entrySet()) {
            if (onlyFiles != null && !onlyFiles.contains(e.getKey())) {
                continue;
            }
            ContextFile meta = c.manifest().files().stream().filter(f -> f.path().equals(e.getKey())).findFirst().orElse(null);
            u.append("\n===== FILE: ").append(e.getKey());
            if (meta != null && meta.contentStatus() == ContextFile.ContentStatus.TRUNCATED) {
                u.append(" (TRUNCATED: ").append(meta.note()).append(')');
            }
            u.append(" =====\n").append(ReviewPromptBuilder.numbered(e.getValue()));
        }
        u.append("\n# SNAPSHOT\ncommit: ").append(c.manifest().commitSha()).append('\n');
        return u.toString();
    }

    private static String names(Object[] values) {
        return Arrays.stream(values).map(v -> "\"" + v + "\"").collect(Collectors.joining(" | "));
    }
}
