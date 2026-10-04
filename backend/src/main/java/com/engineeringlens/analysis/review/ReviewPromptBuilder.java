package com.engineeringlens.analysis.review;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextFile;
import com.engineeringlens.analysis.deterministic.Signal;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.ReviewDocument.Personalization;
import com.engineeringlens.analysis.review.model.RubricDimension;

import tools.jackson.databind.ObjectMapper;

/**
 * Builds the two review prompts. Provider-neutral text: adapters decide how it's sent.
 * <ol>
 * <li><b>Assessment</b>: code, signals and rubric, but nothing about the developer, so the verdicts can't
 * depend on who is reading. Contains source code, so it is never logged or stored.</li>
 * <li><b>Teaching</b>: the validated review plus the developer's profile, and no source code. It may only
 * write advice and a learning plan.</li>
 * </ol>
 * One prompt with "don't let the profile change the assessment" wasn't enough: on a real repository the
 * same model rated the same code STRONG for a student and SOLID for a senior engineer.
 */
@Component
public class ReviewPromptBuilder {

    private final ObjectMapper json;

    public ReviewPromptBuilder(ObjectMapper json) {
        this.json = json;
    }

    public AiPrompt assessment(LoadedContext loaded) {
        return new AiPrompt(system(), user(loaded));
    }

    public AiPrompt teaching(ReviewDocument neutralReview, LoadedContext loaded, Personalization personalization,
            ReviewPersonalizer personalizer) {
        return new AiPrompt(teachingSystem(personalizer.guidance(personalization)),
                "# DEVELOPER PROFILE\naudience: " + personalization.audience() + " (" + personalization.basis() + ")\n"
                        + (loaded.developer() == null ? "no profile" : json.writeValueAsString(loaded.developer()))
                        + "\n\n# THE ENGINEERING REVIEW (final; do not change it)\n" + json.writeValueAsString(neutralReview) + "\n");
    }

    /** Same inputs, plus exactly what the validator rejected. Models are stateless, so the context is resent. */
    public AiPrompt repair(AiPrompt original, String problem) {
        return new AiPrompt(original.system(), original.user() + "\n\n# YOUR PREVIOUS ANSWER WAS REJECTED\n"
                + "The validator rejected it: " + problem + "\n"
                + "Return ONLY one JSON object that follows the OUTPUT CONTRACT exactly: all required fields and only the "
                + "listed enum values. Be concise so the answer is not cut off.");
    }

    // ---- system: role, rules, rubric, contract ----------------------------------------------------

    static String system() {
        StringBuilder s = new StringBuilder();
        s.append("""
                # ROLE
                You are a senior software engineer and mentor reviewing a real software project written by a developer
                who wants to learn. Your review is evidence-based, constructive and honest about uncertainty.

                # PRIMARY GOAL
                Help the developer understand what they did well, what could improve, why it matters, how their decisions
                behave as the system grows, and what they should learn next. The aim is engineering judgement, not a grade.

                # RULES
                1. Base every claim only on the supplied inputs (profile, signals, files). Never invent files, code, runtime
                   behaviour, traffic, latency or metrics.
                2. Distinguish observation from interpretation. Deterministic signals are FACTS; your text is INTERPRETATION.
                   Say "No recognised test files were detected", never "this project has no tests", unless proven.
                3. You saw only SELECTED files, some truncated. Never claim something is absent from the whole repository
                   because it is absent from what you were shown.
                4. If evidence is insufficient for a dimension, set assessment NOT_ASSESSABLE and say why. Do not fabricate.
                5. Do not treat more architecture as automatically better. Judge choices against the project's actual size
                   and purpose; a simple design can be the right one. Record such judgements as trade-offs.
                6. Never claim knowledge of any company's internal standards. Refer to
                   "industry-oriented software engineering practices", never "how FAANG/MAANG engineers do it".
                7. Scale statements are scenarios, not predictions: "at substantially higher traffic this could become a
                   bottleneck because…", never "at 1 million users this WILL fail". Give each a confidence.
                8. Evidence: cite {"file": "<exact path from the inputs>", "lineStart": n, "lineEnd": m} using the line numbers
                   shown next to the code, or lineStart/lineEnd null when you cannot point at exact lines, or
                   {"signalId": "<exact ruleId from the signals>"}. Never invent paths, line numbers or signal ids.
                9. Prefer a few high-value findings over many weak ones. At most 5 priorityActions and 5 positiveHighlights.
                10. You are deliberately not told who wrote this code: judge the code alone, against the same bar for everyone.
                    Write explanations for a developer with working experience. Leave every personalizedAdvice as [] and keep
                    the learning plan general; a separate step tailors them to the developer.
                11. Plain text only inside strings: no Markdown headings, no HTML. Short code snippets in exampleApproach are fine.
                12. Return only the JSON object described in OUTPUT CONTRACT. No Markdown fences, no commentary.
                """);
        s.append("\n# REVIEW RUBRIC\nAssess every dimension below (all 16, in this order). "
                + "FRONTEND_CLIENT_ENGINEERING applies only if a frontend/client exists; otherwise NOT_APPLICABLE with "
                + "assessment NOT_ASSESSABLE.\n");
        for (RubricDimension d : RubricDimension.values()) {
            s.append("- ").append(d.name()).append(" (\"").append(d.displayName()).append("\"): ")
                    .append(String.join(" ", d.questions())).append('\n');
        }
        s.append("\n# OUTPUT CONTRACT\n").append(CONTRACT);
        return s.toString();
    }

    static final String CONTRACT = """
            Return exactly one JSON object with these fields (reviewSchemaVersion must be %d):
            {
              "reviewSchemaVersion": %d,
              "overallAssessment": {"level": ASSESSMENT, "confidence": CONFIDENCE, "summary": str,
                                    "strongestAreas": [str], "highestPriorityAreas": [str]},
              "executiveSummary": {"whatThisProjectDoes": str, "engineeringSummary": str, "strongestAspect": str,
                                   "biggestOpportunity": str, "overallScaleConcern": str},
              "projectUnderstanding": {"projectType": str, "architectureSummary": str, "detectedStack": [str],
                                       "importantComponents": [str]},
              "dimensions": [ exactly 16 objects, one per rubric id:
                {"id": RUBRIC_ID, "name": str, "applicability": "APPLICABLE"|"NOT_APPLICABLE", "assessment": ASSESSMENT,
                 "confidence": CONFIDENCE, "summary": str,
                 "strengths": [{"title": str, "description": str, "evidence": [EVIDENCE]}],
                 "concerns": [{"id": str like "ARCH-001", "title": str, "severity": SEVERITY, "confidence": CONFIDENCE,
                               "description": str, "whyItMatters": str, "engineeringImpact": str,
                               "scaleImpact": {"currentScale": str|null, "tenX": str|null, "hundredX": str|null,
                                               "largeScale": str|null, "confidence": CONFIDENCE} | null,
                               "evidence": [EVIDENCE], "recommendation": str, "suggestedDirection": str|null,
                               "learningValue": {"currentLevel": str, "nextLevel": str, "advancedLevel": str} | null}],
                 "tradeoffs": [{"decision": str, "benefit": str, "cost": str, "assessment": TRADEOFF}],
                 "personalizedAdvice": [str]} ],
              "crossCuttingFindings": [{"id": str like "F-001", "title": str, "category": RUBRIC_ID, "severity": SEVERITY,
                  "confidence": CONFIDENCE, "description": str, "whyItMatters": str, "engineeringImpact": str,
                  "evidence": [EVIDENCE], "scaleImpact": {...as above} | null, "recommendation": str,
                  "exampleApproach": str|null}],
              "featureEngineeringReview": [ only features you can identify confidently from the code, else [] :
                  {"feature": str, "correctness": {"assessment": ASSESSMENT, "summary": str},
                   "implementationQuality": {"assessment": ASSESSMENT, "summary": str},
                   "edgeCases": {"handled": [str], "missing": [str]}, "failureModes": [str],
                   "scaleConsiderations": [str], "recommendations": [str], "evidence": [EVIDENCE]}],
              "scaleReadiness": {"summary": str,
                  "trafficGrowth": AREA, "dataGrowth": AREA, "concurrency": AREA, "failureRecovery": AREA,
                  "operationalComplexity": AREA,
                  "mostLikelyBottlenecks": [{"component": str, "reason": str, "confidence": CONFIDENCE}]},
              "priorityActions": [ at most 5: {"priority": 1.., "title": str, "reason": str, "expectedBenefit": str,
                  "difficulty": "LOW"|"MEDIUM"|"HIGH", "relatedDimensions": [RUBRIC_ID]}],
              "personalizedLearningPlan": {"youAlreadyDoWell": [str],
                  "nextThingsToLearn": [{"topic": str, "why": str, "connectionToProject": str, "suggestedOrder": 1..}],
                  "advancedTopics": [str]},
              "positiveHighlights": [ at most 5: {"title": str, "description": str, "whyThisIsGood": str,
                  "evidence": [EVIDENCE]}],
              "reviewLimitations": [str]  (e.g. runtime traffic not measured; only selected files were provided)
            }
            Where:
            ASSESSMENT = "STRONG" | "SOLID" | "DEVELOPING" | "NEEDS_ATTENTION" | "NOT_ASSESSABLE"
            CONFIDENCE = "HIGH" | "MEDIUM" | "LOW"
            SEVERITY = "HIGH" | "MEDIUM" | "LOW"
            TRADEOFF = "STRONG" | "REASONABLE" | "CONTEXT_DEPENDENT" | "QUESTIONABLE"
            AREA = {"assessment": ASSESSMENT, "concerns": [str]}
            RUBRIC_ID = one of the 16 rubric ids, exactly as written
            EVIDENCE = {"file": str, "lineStart": int|null, "lineEnd": int|null} or {"signalId": str}
            All arrays must be present (use [] when empty). Do not include reviewMetadata: Engiens adds it.
            """.formatted(ReviewDocument.SCHEMA_VERSION, ReviewDocument.SCHEMA_VERSION);

    // ---- teaching: advice for this developer, from the finished review ---------------------------

    static String teachingSystem(String audienceGuidance) {
        return """
                # ROLE
                You are a senior software engineer mentoring the developer described below. Another reviewer has already
                assessed their project; that review is final.

                # TASK
                Write advice for THIS developer: for each rubric dimension, 0 to 3 short, concrete next steps grounded in that
                dimension's findings, and a learning plan connected to this project.

                # RULES
                1. Never change, restate or contradict an assessment, severity or finding. You only teach.
                2. Use only what the review says. Don't invent files, code or problems.
                3. Never claim knowledge of any company's internal standards.
                4. Plain text inside strings: no Markdown, no HTML.
                5. Return only the JSON object below. No Markdown fences, no commentary.

                # PERSONALISATION
                """ + audienceGuidance + """


                # OUTPUT CONTRACT
                {
                  "dimensions": [ {"id": RUBRIC_ID, "personalizedAdvice": [str]} ],   (one entry per dimension; [] if nothing useful)
                  "personalizedLearningPlan": {"youAlreadyDoWell": [str],
                      "nextThingsToLearn": [{"topic": str, "why": str, "connectionToProject": str, "suggestedOrder": 1..}],
                      "advancedTopics": [str]}
                }
                RUBRIC_ID = one of the dimension ids used in the review, exactly as written.
                """;
    }

    // ---- user: the evidence (nothing about the developer) -------------------------------------------

    String user(LoadedContext loaded) {
        AnalysisContext ctx = loaded.context();
        StringBuilder u = new StringBuilder();
        u.append("# REPOSITORY PROFILE (deterministic; every detection lists its evidence)\n")
                .append(json.writeValueAsString(ctx.profile())).append("\n\n");

        u.append("# DETERMINISTIC SIGNALS (facts Engiens verified; cite them by ruleId)\n");
        List<Signal> signals = ctx.analysis().signals();
        for (Signal s : signals) {
            u.append("- ").append(s.ruleId()).append(" [").append(s.severity()).append(", ").append(s.confidence()).append("] ")
                    .append(s.message());
            if (s.file() != null) {
                u.append(" (").append(s.file()).append(s.line() == null ? "" : ":" + s.line()).append(')');
            }
            u.append('\n');
        }

        u.append("\n# SELECTED FILES (why each was chosen; content follows below)\n");
        for (ContextFile f : ctx.manifest().files()) {
            u.append("- ").append(f.path()).append(" | ").append(f.contentStatus())
                    .append(f.note() == null ? "" : " (" + f.note() + ")")
                    .append(" | ").append(String.join("; ", f.reasons())).append('\n');
        }
        if (!loaded.unavailableFiles().isEmpty()) {
            u.append("Unavailable at review time (do not cite their contents): ").append(String.join(", ", loaded.unavailableFiles()))
                    .append('\n');
        }

        u.append("\n# FILE CONTENTS (line numbers are shown before each line; cite them exactly)\n");
        for (Map.Entry<String, String> e : ctx.contents().entrySet()) {
            ContextFile meta = ctx.manifest().files().stream().filter(f -> f.path().equals(e.getKey())).findFirst().orElse(null);
            u.append("\n===== FILE: ").append(e.getKey());
            if (meta != null && meta.contentStatus() == ContextFile.ContentStatus.TRUNCATED) {
                u.append(" (TRUNCATED: ").append(meta.note()).append(')');
            }
            u.append(" =====\n").append(numbered(e.getValue()));
        }

        u.append("\n# ANALYSIS METADATA\n")
                .append("commit: ").append(ctx.manifest().commitSha()).append('\n')
                .append("files in repository inventory: ").append(ctx.profile().inventory().totalFiles())
                .append(" (relevant: ").append(ctx.profile().inventory().relevantFiles()).append(")\n")
                .append("files shown to you: ").append(ctx.contents().size()).append('\n')
                .append("rubric version: ").append(RubricDimension.RUBRIC_VERSION).append(", review schema version: ")
                .append(ReviewDocument.SCHEMA_VERSION).append('\n');
        return u.toString();
    }

    static String numbered(String text) {
        String[] lines = text.split("\n", -1);
        int count = text.endsWith("\n") ? lines.length - 1 : lines.length;
        int width = String.valueOf(Math.max(count, 1)).length();
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> String.format("%" + width + "d| %s", i + 1, lines[i]))
                .collect(Collectors.joining("\n", "", "\n"));
    }
}
