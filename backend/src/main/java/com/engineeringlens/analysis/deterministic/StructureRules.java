package com.engineeringlens.analysis.deterministic;

import static com.engineeringlens.analysis.deterministic.Signal.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;
import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.profile.RepositoryProfile.StructureSignal;

/**
 * Code-quality, architecture and error-handling signals from structure and file sizes. They say what
 * exists ("service-like directory detected"), never whether it's used well: that needs reasoning.
 */
public final class StructureRules {

    private StructureRules() {
    }

    /** About 1,000+ lines of typical code. Large enough to be worth a look, not a verdict. */
    public static final long LARGE_SOURCE_FILE_BYTES = 50_000;
    private static final int MAX_LARGE_FILES = 5;

    public static final AnalysisRule LARGE_SOURCE_FILE = AnalysisRule.of("CODE_QUALITY.LARGE_SOURCE_FILE", ReviewDimension.CODE_QUALITY,
            "Source files over 50 KB (the largest five). Size is evidence for later review, not a violation.",
            (rule, in) -> in.relevant().stream()
                    .filter(f -> RepoPaths.isCode(f.language()) && f.sizeBytes() > LARGE_SOURCE_FILE_BYTES)
                    .sorted(Comparator.comparingLong(InventoryFile::sizeBytes).reversed().thenComparing(InventoryFile::path))
                    .limit(MAX_LARGE_FILES)
                    .map(f -> Signal.of(rule, Severity.INFO, Confidence.HIGH,
                            f.path() + " is " + Math.round(f.sizeBytes() / 1024.0) + " KB, large for a single source file.",
                            evidence("sizeBytes", f.sizeBytes(), "thresholdBytes", LARGE_SOURCE_FILE_BYTES)).at(f.path(), null))
                    .toList());

    private static final Map<String, String> LAYERS = new LinkedHashMap<>();
    static {
        LAYERS.put("Route/controller directory", "route/controller");
        LAYERS.put("Service directory", "service");
        LAYERS.put("Repository/DAO directory", "repository/DAO");
        LAYERS.put("Model/entity directory", "model/entity");
        LAYERS.put("Schema/DTO directory", "schema/DTO");
        LAYERS.put("Middleware directory", "middleware");
    }

    public static final AnalysisRule LAYER_DIRECTORIES = AnalysisRule.of("ARCHITECTURE.LAYER_DIRECTORIES", ReviewDimension.ARCHITECTURE,
            "Directories named like application layers (controllers, services, repositories...).",
            (rule, in) -> {
                Map<String, Object> found = new LinkedHashMap<>();
                for (StructureSignal s : in.profile().structureSignals()) {
                    if (LAYERS.containsKey(s.signal())) {
                        found.put(LAYERS.get(s.signal()), s.paths());
                    }
                }
                return found.isEmpty() ? List.of()
                        : List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH,
                                "Layer-like directories detected: " + String.join(", ", found.keySet()) + ".", found));
            });

    public static final AnalysisRule FRONTEND_BACKEND_SPLIT = AnalysisRule.of("ARCHITECTURE.FRONTEND_BACKEND_SPLIT",
            ReviewDimension.ARCHITECTURE, "Separate top-level frontend and backend directories.",
            (rule, in) -> {
                Map<String, List<String>> s = new LinkedHashMap<>();
                in.profile().structureSignals().forEach(sig -> s.put(sig.signal(), sig.paths()));
                if (!s.containsKey("Frontend directory") || !s.containsKey("Backend directory")) {
                    return List.of();
                }
                return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH, "Separate frontend and backend directories detected.",
                        evidence("frontend", s.get("Frontend directory"), "backend", s.get("Backend directory"))));
            });

    public static final AnalysisRule ENTRYPOINTS = AnalysisRule.of("ARCHITECTURE.ENTRYPOINTS", ReviewDimension.ARCHITECTURE,
            "Conventional application entry points (main.py, server.ts, *Application.java...).",
            (rule, in) -> {
                List<String> e = in.profile().entrypoints();
                return e.isEmpty() ? List.of()
                        : List.of(Signal.of(rule, Severity.INFO, Confidence.MEDIUM,
                                "Application entry point" + (e.size() == 1 ? "" : "s") + " detected: " + String.join(", ", e) + ".",
                                evidence("entrypoints", e)));
            });

    private static final Pattern HANDLER_CODE = Pattern.compile(
            "@(Rest)?ControllerAdvice|@ExceptionHandler\\b|\\.exception_handler\\(|@\\w+\\.errorhandler|\\.errorhandler\\("
                    + "|\\(\\s*err\\s*,\\s*req\\s*,\\s*res\\s*,\\s*next\\s*\\)|@Catch\\(|implements\\s+ExceptionFilter");
    private static final Set<String> HANDLER_WORDS = Set.of("handler", "handlers", "middleware", "filter", "advice");

    public static final AnalysisRule CENTRAL_ERROR_HANDLER = AnalysisRule.of("ERROR_HANDLING.CENTRAL_HANDLER",
            ReviewDimension.ERROR_HANDLING,
            "Centralised error handling recognised by file name (e.g. GlobalExceptionHandler) or by framework markers in inspected code.",
            (rule, in) -> {
                List<String> byCode = new ArrayList<>();
                in.texts().all().forEach((path, text) -> {
                    if (HANDLER_CODE.matcher(text).find()) {
                        byCode.add(path);
                    }
                });
                List<String> byName = in.relevant().stream()
                        .filter(f -> RepoPaths.isCode(f.language()) && !RepoPaths.isTestFile(f.path(), f.language()))
                        .map(InventoryFile::path)
                        .filter(p -> {
                            List<String> words = RepoPaths.nameWords(p);
                            return (words.contains("error") || words.contains("exception") || words.contains("errors"))
                                    && words.stream().anyMatch(HANDLER_WORDS::contains);
                        })
                        .filter(p -> !byCode.contains(p))
                        .limit(5).toList();
                if (byCode.isEmpty() && byName.isEmpty()) {
                    return List.of(); // absence by name proves nothing, so no "missing" signal
                }
                return List.of(Signal.of(rule, Severity.INFO, byCode.isEmpty() ? Confidence.MEDIUM : Confidence.HIGH,
                        "Centralised error handling detected.",
                        evidence("filesWithHandlerCode", byCode.stream().limit(5).toList(), "filesNamedLikeHandlers", byName)));
            });

    static final List<AnalysisRule> ALL = List.of(LARGE_SOURCE_FILE, LAYER_DIRECTORIES, FRONTEND_BACKEND_SPLIT, ENTRYPOINTS,
            CENTRAL_ERROR_HANDLER);
}
