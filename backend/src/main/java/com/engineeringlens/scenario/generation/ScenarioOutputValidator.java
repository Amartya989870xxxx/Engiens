package com.engineeringlens.scenario.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.InvalidAiOutputException;
import com.engineeringlens.analysis.review.LoadedContext;
import com.engineeringlens.analysis.review.ReviewValidator;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.execution.ScenarioExecutionService;
import com.engineeringlens.scenario.execution.WorkspaceFile;
import com.engineeringlens.scenario.generation.ScenarioContextBuilder.ScenarioContext;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Treats generation output as untrusted input, like the review validator: parse, bind to the strict
 * contract, validate, and make every file reference point at something real. Anything unusable throws
 * {@link InvalidAiOutputException} with a short reason (sent back for one repair attempt). Whether code
 * actually runs is checked separately, in the sandbox, by {@link HarnessValidator}.
 */
@Component
public class ScenarioOutputValidator {

    static final int MAX_WORKSPACE_FILE_BYTES = 24 * 1024;
    static final int MAX_WORKSPACE_BYTES = 64 * 1024;
    static final int MAX_CHECKS_BYTES = 32 * 1024;
    private static final int MAX_TEXT = 6000;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .build();

    private final Validator validator;

    public ScenarioOutputValidator(Validator validator) {
        this.validator = validator;
    }

    /** At least {@code needed} usable outlines, each with real evidence, an allowed role and a feasible mode. */
    public ScenarioPlan plan(String raw, ScenarioLab lab, ScenarioContext ctx, int needed) {
        ScenarioPlan plan = bind(raw, ScenarioPlan.class);
        if (plan.scenarioPlanSchemaVersion() != ScenarioPlan.SCHEMA_VERSION) {
            throw new InvalidAiOutputException("scenarioPlanSchemaVersion must be " + ScenarioPlan.SCHEMA_VERSION);
        }
        boolean broad = lab.getRoles().contains(ScenarioRole.BROAD_ENGINEERING);
        Set<String> titles = new HashSet<>();
        List<ScenarioPlan.Outline> usable = new ArrayList<>();
        for (ScenarioPlan.Outline o : plan.outlines()) {
            boolean roleAllowed = broad ? o.role() != ScenarioRole.BROAD_ENGINEERING : lab.getRoles().contains(o.role());
            List<ScenarioPlan.Grounding> grounding = o.groundedIn().stream().map(g -> grounding(g, ctx.loaded()))
                    .filter(g -> g != null).toList();
            if (!roleAllowed || grounding.isEmpty() || !titles.add(o.title().strip().toLowerCase())) {
                continue;
            }
            // A CODE outline in a language the sandbox can't run here becomes a reasoning scenario rather than being lost.
            boolean code = o.mode() == ExecutionCapability.CODE && o.language() != null && ctx.executableLanguages().contains(o.language());
            usable.add(new ScenarioPlan.Outline(o.key(), o.title().strip(), o.role(), o.category(), o.difficulty(),
                    code ? ExecutionCapability.CODE : ExecutionCapability.APPROACH_ONLY, code ? o.language() : null, o.problem(),
                    grounding, o.expectedConcepts(), o.whyItFitsTheLevel()));
        }
        if (usable.size() < needed) {
            throw new InvalidAiOutputException("Only " + usable.size() + " outlines are usable but at least " + needed
                    + " are needed. Every outline needs a unique title, one of the allowed roles, and groundedIn files copied exactly "
                    + "from the FILE CONTENTS headers.");
        }
        return new ScenarioPlan(plan.scenarioPlanSchemaVersion(), plan.repositorySummary(), List.copyOf(usable));
    }

    /** One built scenario: complete, consistent with its outline, within size limits, with real evidence. */
    public GeneratedScenario scenario(String raw, ScenarioPlan.Outline outline, ScenarioContext ctx) {
        GeneratedScenario g = bind(raw, GeneratedScenario.class);
        if (g.scenarioSchemaVersion() != 1) {
            throw new InvalidAiOutputException("scenarioSchemaVersion must be 1");
        }
        List<GeneratedScenario.Evidence> evidence = g.evidence().stream().map(e -> evidence(e, ctx.loaded())).filter(e -> e != null).toList();
        if (evidence.isEmpty()) {
            // Fall back to what the outline was grounded in (already verified) rather than showing no evidence.
            evidence = outline.groundedIn().stream()
                    .map(gr -> new GeneratedScenario.Evidence(gr.file(), gr.lineStart(), gr.lineEnd(), gr.why())).toList();
        }
        boolean code = outline.mode() == ExecutionCapability.CODE;
        GeneratedScenario.Workspace workspace = null;
        GeneratedScenario.Checks checks = null;
        GeneratedScenario.ReferenceSolution solution = null;
        if (code) {
            workspace = workspace(g.workspace(), outline.language());
            checks = checks(g.checks());
            solution = solution(g.referenceSolution(), workspace);
        }
        return new GeneratedScenario(g.scenarioSchemaVersion(), g.title().strip(), cap(g.summary()), cap(g.incident()), cap(g.context()),
                cap(g.task()), g.expectedBehaviour(), g.constraints(), evidence, g.expectedConcepts(), g.rubric(),
                cap(g.referenceReasoning()), workspace, checks, solution);
    }

    private static GeneratedScenario.Workspace workspace(GeneratedScenario.Workspace w, ScenarioLanguage language) {
        if (w == null) {
            throw new InvalidAiOutputException("A CODE scenario needs a workspace");
        }
        if (w.language() != language) {
            throw new InvalidAiOutputException("workspace.language must be " + language);
        }
        List<GeneratedScenario.File> files = w.files().stream()
                .map(f -> new GeneratedScenario.File(f.path().strip().replaceFirst("^(\\./|/)", ""), f.content(),
                        f.editable() == null || f.editable()))
                .toList();
        try {
            ScenarioExecutionService.validateWorkspace(files.stream().map(f -> new WorkspaceFile(f.path(), f.content())).toList());
        } catch (ApiException e) {
            throw new InvalidAiOutputException("Invalid workspace: " + e.getMessage());
        }
        if (files.stream().noneMatch(GeneratedScenario.File::editable)) {
            throw new InvalidAiOutputException("At least one workspace file must be editable");
        }
        int total = 0;
        for (GeneratedScenario.File f : files) {
            int bytes = f.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > MAX_WORKSPACE_FILE_BYTES) {
                throw new InvalidAiOutputException("Workspace file " + f.path() + " is too large; keep each file under 24 KB");
            }
            total += bytes;
        }
        if (total > MAX_WORKSPACE_BYTES) {
            throw new InvalidAiOutputException("The workspace is too large; keep it under 64 KB in total");
        }
        return new GeneratedScenario.Workspace(language, files);
    }

    private static GeneratedScenario.Checks checks(GeneratedScenario.Checks c) {
        if (c == null) {
            throw new InvalidAiOutputException("A CODE scenario needs checks");
        }
        if (c.source().length() > MAX_CHECKS_BYTES) {
            throw new InvalidAiOutputException("The checks file is too large; keep it under 32 KB");
        }
        List<String> names = c.checkNames().stream().map(String::strip).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw new InvalidAiOutputException("checkNames must be unique");
        }
        return new GeneratedScenario.Checks(c.source(), names);
    }

    private static GeneratedScenario.ReferenceSolution solution(GeneratedScenario.ReferenceSolution s, GeneratedScenario.Workspace w) {
        if (s == null) {
            throw new InvalidAiOutputException("A CODE scenario needs a referenceSolution");
        }
        Map<String, Boolean> editable = new LinkedHashMap<>();
        w.files().forEach(f -> editable.put(f.path(), f.editable()));
        List<GeneratedScenario.SolutionFile> files = new ArrayList<>();
        for (GeneratedScenario.SolutionFile f : s.files()) {
            String path = f.path().strip().replaceFirst("^(\\./|/)", "");
            if (!Boolean.TRUE.equals(editable.get(path))) {
                throw new InvalidAiOutputException("referenceSolution file " + path + " must be one of the editable workspace files");
            }
            files.add(new GeneratedScenario.SolutionFile(path, f.content()));
        }
        return new GeneratedScenario.ReferenceSolution(files, s.explanation());
    }

    /** A grounding must name a real file the model was shown; lines it couldn't have seen are dropped. */
    private static ScenarioPlan.Grounding grounding(ScenarioPlan.Grounding g, LoadedContext loaded) {
        String file = g.file().strip().replaceFirst("^(\\./|/)", "");
        if (!loaded.context().contents().containsKey(file)) {
            return null;
        }
        Integer[] lines = lines(g.lineStart(), g.lineEnd(), loaded.includedLines().get(file));
        return new ScenarioPlan.Grounding(file, lines[0], lines[1], g.why());
    }

    private static GeneratedScenario.Evidence evidence(GeneratedScenario.Evidence e, LoadedContext loaded) {
        String file = e.file().strip().replaceFirst("^(\\./|/)", "");
        if (!loaded.repositoryPaths().contains(file)) {
            return null;
        }
        Integer[] lines = lines(e.lineStart(), e.lineEnd(), loaded.includedLines().get(file));
        return new GeneratedScenario.Evidence(file, lines[0], lines[1], e.explanation());
    }

    private static Integer[] lines(Integer start, Integer end, Integer shown) {
        if (start == null || shown == null || start < 1 || start > shown) {
            return new Integer[] { null, null };
        }
        int e = end == null || end < start ? start : Math.min(end, shown);
        return new Integer[] { start, e };
    }

    private <T> T bind(String raw, Class<T> type) {
        T value;
        try {
            value = MAPPER.readValue(ReviewValidator.stripFences(raw), type);
        } catch (RuntimeException e) {
            throw new InvalidAiOutputException("Not valid JSON for the contract: " + firstLine(e.getMessage()));
        }
        if (value == null) {
            throw new InvalidAiOutputException("The answer must be a single JSON object.");
        }
        List<String> problems = new ArrayList<>();
        for (ConstraintViolation<T> v : validator.validate(value)) {
            problems.add(v.getPropertyPath() + " " + v.getMessage());
        }
        if (!problems.isEmpty()) {
            throw new InvalidAiOutputException(problems.stream().sorted().limit(6).collect(Collectors.joining("; ")));
        }
        return value;
    }

    private static String cap(String s) {
        String t = s.strip();
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable structure";
        }
        String line = message.lines().findFirst().orElse(message);
        return line.length() <= 200 ? line : line.substring(0, 200);
    }
}
