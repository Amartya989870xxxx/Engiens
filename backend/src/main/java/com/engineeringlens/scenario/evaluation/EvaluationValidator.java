package com.engineeringlens.scenario.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.InvalidAiOutputException;
import com.engineeringlens.analysis.review.ReviewValidator;
import com.engineeringlens.scenario.model.LabSummary;
import com.engineeringlens.scenario.model.LabTeaching;
import com.engineeringlens.scenario.model.ScenarioEvaluation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Evaluation output is untrusted input: parsed, bound to the strict contract and validated before it's stored. */
@Component
public class EvaluationValidator {

    private static final int MAX_LEARNING_POINTS = 3;
    private static final int MAX_RECOMMENDATIONS = 5;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .build();

    private final Validator validator;

    public EvaluationValidator(Validator validator) {
        this.validator = validator;
    }

    public ScenarioEvaluation evaluation(String raw) {
        ScenarioEvaluation e = bind(raw, ScenarioEvaluation.class);
        if (e.scenarioEvaluationSchemaVersion() != ScenarioEvaluation.SCHEMA_VERSION) {
            throw new InvalidAiOutputException("scenarioEvaluationSchemaVersion must be " + ScenarioEvaluation.SCHEMA_VERSION);
        }
        return e;
    }

    public LabSummary summary(String raw) {
        LabSummary s = bind(raw, LabSummary.class);
        if (s.labSummarySchemaVersion() != LabSummary.SCHEMA_VERSION) {
            throw new InvalidAiOutputException("labSummarySchemaVersion must be " + LabSummary.SCHEMA_VERSION);
        }
        return s;
    }

    /** Learning points for unknown scenarios are dropped; lists are capped. There is no field for a verdict. */
    public LabTeaching teaching(String raw, Set<String> scenarioIds) {
        LabTeaching t = bind(raw, LabTeaching.class);
        return new LabTeaching(
                t.scenarioLearning().stream().filter(l -> scenarioIds.contains(l.scenarioId()))
                        .map(l -> new LabTeaching.ScenarioLearning(l.scenarioId(), l.learningPoints().stream().limit(MAX_LEARNING_POINTS).toList()))
                        .toList(),
                t.learningRecommendations().stream().limit(MAX_RECOMMENDATIONS).toList());
    }

    private <T> T bind(String raw, Class<T> type) {
        T value;
        try {
            value = MAPPER.readValue(ReviewValidator.stripFences(raw), type);
        } catch (RuntimeException e) {
            String m = e.getMessage() == null ? "unreadable" : e.getMessage().lines().findFirst().orElse("");
            throw new InvalidAiOutputException("Not valid JSON for the contract: " + (m.length() <= 200 ? m : m.substring(0, 200)));
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
}
