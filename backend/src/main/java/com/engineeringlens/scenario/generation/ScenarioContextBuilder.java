package com.engineeringlens.scenario.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.AnalysisRun;
import com.engineeringlens.analysis.AnalysisRunRepository;
import com.engineeringlens.analysis.review.AnalysisContextLoader;
import com.engineeringlens.analysis.review.LoadedContext;
import com.engineeringlens.analysis.review.StoredReviewRepository;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.ReviewEnums.Severity;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLanguage;

import tools.jackson.databind.ObjectMapper;

/**
 * Everything scenario generation may use, from the lab's own snapshot: the review's analysis run (same
 * commit, same selected files, re-fetched and hash-verified by the existing loader), the review's concerns
 * as hints when the lab came from a review, and which languages the sandbox can execute for this code.
 */
@Component
public class ScenarioContextBuilder {

    private static final int MAX_HINTS = 15;

    private final AnalysisContextLoader loader;
    private final AnalysisRunRepository analysisRuns;
    private final ImportedRepoRepository repositories;
    private final StoredReviewRepository reviews;
    private final ObjectMapper json;

    public ScenarioContextBuilder(AnalysisContextLoader loader, AnalysisRunRepository analysisRuns, ImportedRepoRepository repositories,
            StoredReviewRepository reviews, ObjectMapper json) {
        this.loader = loader;
        this.analysisRuns = analysisRuns;
        this.repositories = repositories;
        this.reviews = reviews;
        this.json = json;
    }

    /**
     * @param hints               the originating review's concerns (empty for a direct lab): "what's weak here"
     * @param executableLanguages languages present in the selected code that the sandbox can run
     */
    public record ScenarioContext(ImportedRepo repository, LoadedContext loaded, List<String> hints, Set<ScenarioLanguage> executableLanguages) {
    }

    public ScenarioContext build(ScenarioLab lab, boolean sandboxAvailable) {
        ImportedRepo repo = repositories.findById(lab.getRepositoryId()).orElseThrow(() -> gone("repository"));
        AnalysisRun run = analysisRuns.findById(lab.getAnalysisRunId()).orElseThrow(() -> gone("prepared analysis"));
        LoadedContext loaded = loader.load(lab.getUserId(), repo, run);
        if (loaded.context().contents().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_CONTEXT_EMPTY",
                    "None of this repository's selected files could be read at the lab's commit, so there is nothing to base scenarios on.");
        }
        Set<ScenarioLanguage> languages = EnumSet.noneOf(ScenarioLanguage.class);
        if (sandboxAvailable) {
            loaded.context().contents().keySet().forEach(path -> {
                ScenarioLanguage l = languageOf(path);
                if (l != null) {
                    languages.add(l);
                }
            });
        }
        return new ScenarioContext(repo, loaded, lab.getReviewId() == null ? List.of() : hints(lab), Set.copyOf(languages));
    }

    /** Which sandbox language a repository file's logic can be extracted into (JSX/TSX logic goes to plain modules). */
    static ScenarioLanguage languageOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".py")) {
            return ScenarioLanguage.PYTHON;
        }
        if (p.endsWith(".java")) {
            return ScenarioLanguage.JAVA;
        }
        if (p.endsWith(".ts") || p.endsWith(".tsx") || p.endsWith(".mts")) {
            return p.endsWith(".d.ts") ? null : ScenarioLanguage.TYPESCRIPT;
        }
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) {
            return ScenarioLanguage.JAVASCRIPT;
        }
        return null;
    }

    /** The review's most serious concerns, one line each, so scenarios can target real weaknesses. */
    private List<String> hints(ScenarioLab lab) {
        return reviews.findById(lab.getReviewId()).map(stored -> {
            ReviewDocument review = json.readValue(stored.getReviewJson(), ReviewDocument.class);
            record Hint(Severity severity, String text) {
            }
            List<Hint> all = new ArrayList<>();
            review.dimensions().forEach(d -> d.concerns().forEach(c -> all.add(new Hint(c.severity(),
                    d.id() + " / " + c.severity() + ": " + c.title() + " - " + c.description() + files(c.evidence())))));
            review.crossCuttingFindings().forEach(f -> all.add(new Hint(f.severity(),
                    f.category() + " / " + f.severity() + ": " + f.title() + " - " + f.description() + files(f.evidence()))));
            return all.stream().sorted(Comparator.comparing(Hint::severity)).limit(MAX_HINTS).map(Hint::text).toList();
        }).orElse(List.of());
    }

    private static String files(List<ReviewDocument.Evidence> evidence) {
        List<String> files = evidence.stream().filter(e -> e.file() != null)
                .map(e -> e.file() + (e.lineStart() == null ? "" : ":" + e.lineStart() + "-" + e.lineEnd())).distinct().limit(3).toList();
        return files.isEmpty() ? "" : " (" + String.join(", ", files) + ")";
    }

    private static ApiException gone(String what) {
        return new ApiException(HttpStatus.CONFLICT, "SCENARIO_CONTEXT_UNAVAILABLE", "This lab's " + what + " is no longer available.");
    }
}
