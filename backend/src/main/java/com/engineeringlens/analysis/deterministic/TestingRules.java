package com.engineeringlens.analysis.deterministic;

import static com.engineeringlens.analysis.deterministic.Signal.evidence;

import java.util.List;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.profile.RepositoryProfile;

/** Whether tests appear to exist, judged only from names and declared dependencies. Never "coverage". */
public final class TestingRules {

    private TestingRules() {
    }

    static final String RECOGNISED_PATTERNS = "test_*.py, *_test.py, *.test.ts, *.spec.ts, *Test.java, *_test.go, "
            + "and code inside test/, tests/, __tests__/ or spec/ folders";

    public static final AnalysisRule TEST_FILES = AnalysisRule.of("TESTING.TEST_FILES", ReviewDimension.TESTING,
            "Counts files recognised as automated tests and their share of relevant files.",
            (rule, in) -> {
                RepositoryProfile.Testing t = in.profile().testing();
                int relevant = in.profile().inventory().relevantFiles();
                if (!t.testsDetected()) {
                    // MEDIUM confidence: an unusual test layout could be missed, so this says "not recognised".
                    return List.of(Signal.of(rule, Severity.LOW, Confidence.MEDIUM,
                            "No recognised automated test files were detected.",
                            evidence("testFileCount", 0, "relevantFileCount", relevant, "recognisedPatterns", RECOGNISED_PATTERNS)));
                }
                return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH,
                        t.testFileCount() + " recognised test " + (t.testFileCount() == 1 ? "file" : "files") + " ("
                                + Math.round(t.testToRelevantRatio() * 1000) / 10.0 + "% of relevant files).",
                        evidence("testFileCount", t.testFileCount(), "relevantFileCount", relevant,
                                "testToRelevantRatio", t.testToRelevantRatio(), "testDirectories", t.testDirectories())));
            });

    public static final AnalysisRule TEST_FRAMEWORKS = AnalysisRule.of("TESTING.TEST_FRAMEWORK", ReviewDimension.TESTING,
            "Reports test frameworks declared as dependencies or configured by well-known files.",
            (rule, in) -> in.profile().testing().frameworks().stream()
                    .map(d -> Signal.of(rule, Severity.INFO, d.confidence(), "Test framework detected: " + d.name() + ".",
                            evidence("framework", d.name(), "evidence", d.evidence())))
                    .toList());

    static final List<AnalysisRule> ALL = List.of(TEST_FILES, TEST_FRAMEWORKS);
}
