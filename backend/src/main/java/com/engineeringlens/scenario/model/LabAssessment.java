package com.engineeringlens.scenario.model;

import java.util.List;

/**
 * A completed lab's stored assessment: the neutral summary plus the personalised teaching (empty when the
 * teaching step failed, with a limitation saying so). Per-scenario verdicts live on each attempt.
 *
 * @param personalizedFor who the learning points were written for (null when teaching was unavailable)
 */
public record LabAssessment(int labAssessmentSchemaVersion, LabSummary.Overall overallAssessment, List<String> strengths,
        List<String> growthAreas, List<LabTeaching.ScenarioLearning> scenarioLearning,
        List<LabTeaching.LearningRecommendation> learningRecommendations, List<String> limitations, String personalizedFor) {

    public static final int SCHEMA_VERSION = 1;
}
