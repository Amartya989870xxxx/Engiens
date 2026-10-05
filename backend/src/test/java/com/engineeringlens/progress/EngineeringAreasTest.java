package com.engineeringlens.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.scenario.ScenarioCategory;

/** The 16 rubric dimensions are the only engineering areas: every Scenario Lab category lands on one of them. */
class EngineeringAreasTest {

    @Test
    void everyScenarioCategoryMapsToARubricDimension() {
        for (ScenarioCategory category : ScenarioCategory.values()) {
            assertThat(EngineeringAreas.areaOf(category, "t", List.of()).area()).as(category.name()).isNotNull()
                    .isIn((Object[]) RubricDimension.values());
        }
        assertThat(EngineeringAreas.areaOf(ScenarioCategory.CACHING, "t", List.of()).area()).isEqualTo(RubricDimension.PERFORMANCE_AND_EFFICIENCY);
        assertThat(EngineeringAreas.areaOf(ScenarioCategory.FRONTEND_CLIENT, "t", List.of()).area())
                .isEqualTo(RubricDimension.FRONTEND_CLIENT_ENGINEERING);
    }

    @Test
    void anAiMlScenarioCountsUnderTheDimensionItsOwnProblemIsAbout() {
        assertThat(map("Retry the model provider safely when it times out", List.of())).isEqualTo(RubricDimension.ERROR_HANDLING_AND_RESILIENCE);
        assertThat(map("Stop prompt injection through retrieved documents", List.of())).isEqualTo(RubricDimension.SECURITY);
        assertThat(map("Cut inference latency", List.of("response caching"))).isEqualTo(RubricDimension.PERFORMANCE_AND_EFFICIENCY);
        assertThat(map("Catch quality regressions before release", List.of("evaluation suite", "golden set")))
                .isEqualTo(RubricDimension.TESTING_AND_QUALITY_ASSURANCE);
        assertThat(map("Parse model output into the contract", List.of("schema validation"))).isEqualTo(RubricDimension.CORRECTNESS_AND_FEATURE_IMPLEMENTATION);
        // Whole-word starts only: "latest" is not "test", "contest" is not "test".
        assertThat(map("Use the latest contest results", List.of())).isEqualTo(RubricDimension.CORRECTNESS_AND_FEATURE_IMPLEMENTATION);
        assertThat(EngineeringAreas.areaOf(ScenarioCategory.AI_ML_ENGINEERING, "Retry on timeouts", List.of()).matched()).isEqualTo("retry");
    }

    private static RubricDimension map(String title, List<String> concepts) {
        return EngineeringAreas.areaOf(ScenarioCategory.AI_ML_ENGINEERING, title, concepts).area();
    }
}
