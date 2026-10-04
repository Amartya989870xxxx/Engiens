package com.engineeringlens.analysis.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextFile;
import com.engineeringlens.analysis.context.ContextManifest;
import com.engineeringlens.analysis.context.ContextProperties;
import com.engineeringlens.analysis.context.DeveloperProfile;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.deterministic.Severity;
import com.engineeringlens.analysis.deterministic.Signal;
import com.engineeringlens.analysis.profile.RepositoryProfile;
import com.engineeringlens.analysis.review.model.ReviewDocument.Personalization;
import com.engineeringlens.analysis.review.model.RubricDimension;

class ReviewPromptAndPersonalizationTest {

    private final ReviewPersonalizer personalizer = new ReviewPersonalizer();
    private final ReviewPromptBuilder prompts = new ReviewPromptBuilder(ReviewFixtures.JSON);

    static final DeveloperProfile STUDENT = new DeveloperProfile("UNDERGRADUATE", 2, null, List.of("Python"), List.of(), List.of(),
            List.of(), "Write cleaner code");
    static final DeveloperProfile SENIOR = new DeveloperProfile("PROFESSIONAL", null, "SIX_TO_TEN_YEARS", List.of("Go"),
            List.of(), List.of(), List.of(), null);

    static LoadedContext loaded(DeveloperProfile developer) {
        RepositoryProfile profile = new RepositoryProfile(1, new RepositoryProfile.Project("orders", "asha", "main", "PUBLIC", "c0ffee", true),
                new RepositoryProfile.InventorySummary(10, 8, 2, 9000), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new RepositoryProfile.Testing(false, 0, 0, List.of(), List.of(), List.of()),
                new RepositoryProfile.Deployment(false, false, false, List.of(), List.of(), List.of(), List.of()),
                new RepositoryProfile.Configuration(false, List.of(), List.of()),
                new RepositoryProfile.Documentation(true, "README.md", false, List.of(), List.of()), List.of(), List.of(), List.of());
        Signal tests = new Signal("TESTING.TEST_FILES", ReviewDimension.TESTING, Severity.LOW, Confidence.MEDIUM,
                "No recognised automated test files were detected.", Map.of(), null, null, true);
        ContextFile main = new ContextFile("app/main.py", "Python", 40, "HIGH", 0.95, List.of("ENTRYPOINT"),
                List.of("application entry point"), List.of(ReviewDimension.ARCHITECTURE), ContextFile.ContentStatus.INCLUDED, 40, 40,
                "abc", null);
        ContextManifest manifest = new ContextManifest(1, "c0ffee", true, ContextProperties.defaults(), developer, List.of(main),
                Map.of(), new ContextManifest.Stats(1, 1, 1, 0, 0, 0, 40));
        AnalysisContext ctx = new AnalysisContext(1, profile, new DeterministicAnalysis(1, 1, List.of("TESTING.TEST_FILES"), List.of(tests)),
                manifest, Map.of("app/main.py", "from fastapi import FastAPI\napp = FastAPI()\n"));
        return new LoadedContext(ctx, Set.of("app/main.py"), Map.of("app/main.py", 2), List.of(), developer);
    }

    @Test
    void audienceFollowsTheProfile() {
        assertThat(personalizer.personalize(STUDENT).audience()).isEqualTo("FOUNDATION");
        assertThat(personalizer.personalize(SENIOR).audience()).isEqualTo("EXPERIENCED");
        assertThat(personalizer.personalize(null).audience()).isEqualTo("INTERMEDIATE");
        assertThat(personalizer.personalize(new DeveloperProfile("UNDERGRADUATE", 4, null, List.of(), List.of(), List.of(), List.of(), null))
                .audience()).isEqualTo("INTERMEDIATE");
    }

    @Test
    void promptCarriesRubricSignalsAndNumberedCode() {
        AiPrompt p = prompts.build(loaded(STUDENT), personalizer.personalize(STUDENT), personalizer);
        for (RubricDimension d : RubricDimension.values()) {
            assertThat(p.system()).contains(d.name());
        }
        assertThat(p.system()).contains("industry-oriented software engineering practices").contains("NOT_ASSESSABLE");
        assertThat(p.user()).contains("TESTING.TEST_FILES [LOW, MEDIUM] No recognised automated test files were detected.")
                .contains("===== FILE: app/main.py =====\n1| from fastapi import FastAPI\n2| app = FastAPI()\n");
        assertThat(p.toString()).doesNotContain("FastAPI"); // never log content
    }

    @Test
    void personalisationChangesOnlyTheTeachingNotTheEvidence() {
        Personalization student = personalizer.personalize(STUDENT);
        Personalization senior = personalizer.personalize(SENIOR);
        AiPrompt a = prompts.build(loaded(STUDENT), student, personalizer);
        AiPrompt b = prompts.build(loaded(SENIOR), senior, personalizer);

        assertThat(a.system()).isNotEqualTo(b.system()); // different guidance for the audience
        String evidenceA = a.user().substring(a.user().indexOf("# REPOSITORY PROFILE"));
        String evidenceB = b.user().substring(b.user().indexOf("# REPOSITORY PROFILE"));
        assertThat(evidenceA).isEqualTo(evidenceB); // identical facts, signals and code
    }

    @Test
    void repairPromptSaysWhatWasWrong() {
        AiPrompt original = prompts.build(loaded(STUDENT), personalizer.personalize(STUDENT), personalizer);
        AiPrompt repair = prompts.repair(original, "dimensions size must be between 16 and 16");
        assertThat(repair.system()).isEqualTo(original.system());
        assertThat(repair.user()).startsWith(original.user()).contains("dimensions size must be between 16 and 16");
    }
}
