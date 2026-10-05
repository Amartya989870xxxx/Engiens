package com.engineeringlens.scenario.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioAttempt;
import com.engineeringlens.scenario.ScenarioAttemptRepository;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLabAssessmentRepository;
import com.engineeringlens.scenario.ScenarioLabRepository;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioRepository;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.model.LabAssessment;
import com.engineeringlens.scenario.model.LabTeaching;
import com.engineeringlens.scenario.model.ScenarioDocument;
import com.engineeringlens.scenario.model.ScenarioEvaluation;
import com.engineeringlens.scenario.model.ScenarioReference;
import com.engineeringlens.scenario.workspace.FileContent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Completed labs as permanent, read-only history under their repository. Only COMPLETED labs appear here:
 * open, failed and cancelled ones are never history, and history never reopens a workspace.
 */
@Service
public class ScenarioHistoryService {

    private static final TypeReference<List<FileContent>> FILES = new TypeReference<>() {
    };

    private final ScenarioLabRepository labs;
    private final ScenarioRepository scenarios;
    private final ScenarioAttemptRepository attempts;
    private final ScenarioLabAssessmentRepository assessments;
    private final ImportedRepoRepository repositories;
    private final ObjectMapper json;

    public ScenarioHistoryService(ScenarioLabRepository labs, ScenarioRepository scenarios, ScenarioAttemptRepository attempts,
            ScenarioLabAssessmentRepository assessments, ImportedRepoRepository repositories, ObjectMapper json) {
        this.labs = labs;
        this.scenarios = scenarios;
        this.attempts = attempts;
        this.assessments = assessments;
        this.repositories = repositories;
        this.json = json;
    }

    /** Compact list, newest first, numbered in completion order. No assessment bodies are loaded. */
    @Transactional(readOnly = true)
    public List<LabHistoryItem> history(UUID userId, UUID repositoryId) {
        repositories.findByIdAndUserId(repositoryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository."));
        List<ScenarioLab> completed = completedLabs(userId, repositoryId);
        List<LabHistoryItem> items = new ArrayList<>();
        for (int i = 0; i < completed.size(); i++) {
            ScenarioLab lab = completed.get(i);
            items.add(new LabHistoryItem(lab.getId(), completed.size() - i, lab.getRoles(), lab.getSeniority(), lab.getScenarioCount(),
                    attempts.findByLabIdOrderByCreatedAtAsc(lab.getId()).size(), lab.getCommitSha(), lab.getReviewId(), lab.getCompletedAt()));
        }
        return items;
    }

    @Transactional(readOnly = true)
    public LabAssessmentResponse assessment(UUID userId, UUID labId) {
        ScenarioLab lab = labs.findByIdAndUserId(labId, userId).orElseThrow(ScenarioHistoryService::notFound);
        if (lab.getStatus() != ScenarioLabStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_ASSESSMENT_NOT_READY", "This lab hasn't been completed, so it has no assessment yet.");
        }
        LabAssessment assessment = assessments.findById(labId).map(a -> json.readValue(a.getAssessmentJson(), LabAssessment.class))
                .orElseThrow(ScenarioHistoryService::notFound);
        Map<String, List<String>> learning = assessment.scenarioLearning().stream()
                .collect(Collectors.toMap(LabTeaching.ScenarioLearning::scenarioId, LabTeaching.ScenarioLearning::learningPoints, (a, b) -> a));
        Map<UUID, ScenarioAttempt> byScenario = attempts.findByLabIdOrderByCreatedAtAsc(labId).stream()
                .collect(Collectors.toMap(ScenarioAttempt::getScenarioId, Function.identity()));
        List<LabAssessmentResponse.ScenarioResult> results = new ArrayList<>();
        for (Scenario s : scenarios.findByLabIdOrderByPositionAsc(labId)) {
            ScenarioAttempt a = byScenario.get(s.getId());
            if (a == null) {
                continue;
            }
            results.add(result(s, a, learning.getOrDefault(s.getId().toString(), List.of())));
        }
        ImportedRepo repo = repositories.findById(lab.getRepositoryId()).orElse(null);
        List<ScenarioLab> completed = completedLabs(userId, lab.getRepositoryId());
        int number = completed.size() - completed.stream().map(ScenarioLab::getId).toList().indexOf(lab.getId());
        return new LabAssessmentResponse(lab.getId(), number, lab.getRepositoryId(), repo == null ? null : repo.getGithubRepoName(),
                repo == null ? null : repo.getGithubUrl(), lab.getReviewId(), lab.getCommitSha(), lab.getRoles(), lab.getSeniority(),
                lab.getScenarioCount(), lab.getCreatedAt(), lab.getCompletedAt(), assessment, results);
    }

    /**
     * One submitted scenario's evaluation, available as soon as it is evaluated, while the lab is still open.
     * Read-only: the submission is frozen. Personal learning points arrive with the lab's final assessment.
     */
    @Transactional(readOnly = true)
    public LabAssessmentResponse.ScenarioResult feedback(UUID userId, UUID labId, UUID scenarioId) {
        ScenarioLab lab = labs.findByIdAndUserId(labId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_LAB_NOT_FOUND", "We couldn't find that Scenario Lab."));
        Scenario s = scenarios.findByIdAndLabId(scenarioId, lab.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_NOT_FOUND", "We couldn't find that scenario."));
        ScenarioAttempt a = attempts.findByScenarioId(s.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_ATTEMPT_NOT_FOUND", "This scenario hasn't been submitted yet."));
        if (a.getEvaluationStatus() != EvaluationStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_FEEDBACK_NOT_READY", "This scenario's evaluation isn't ready yet.");
        }
        List<String> learning = assessments.findById(labId)
                .map(x -> json.readValue(x.getAssessmentJson(), LabAssessment.class).scenarioLearning().stream()
                        .filter(l -> l.scenarioId().equals(s.getId().toString())).flatMap(l -> l.learningPoints().stream()).toList())
                .orElse(List.of());
        return result(s, a, learning);
    }

    private LabAssessmentResponse.ScenarioResult result(Scenario s, ScenarioAttempt a, List<String> learningPoints) {
        return new LabAssessmentResponse.ScenarioResult(s.getId(), s.getPosition(), s.getTitle(), s.getRole(), s.getCategory(),
                s.getDifficulty(), s.getExecutionCapability(), s.getLanguage(), json.readValue(s.getScenarioJson(), ScenarioDocument.class),
                a.getMode(), a.getSubmittedFilesJson() == null ? List.of() : json.readValue(a.getSubmittedFilesJson(), FILES),
                a.getSubmittedApproach(), a.getRunResultJson() == null ? null : json.readValue(a.getRunResultJson(), RunResult.class),
                a.getEvaluationJson() == null ? null : json.readValue(a.getEvaluationJson(), ScenarioEvaluation.class),
                json.readValue(s.getReferenceJson(), ScenarioReference.class), learningPoints, a.getCreatedAt());
    }

    private List<ScenarioLab> completedLabs(UUID userId, UUID repositoryId) {
        return labs.findByRepositoryIdAndUserIdAndStatusOrderByCompletedAtDesc(repositoryId, userId, ScenarioLabStatus.COMPLETED).stream()
                .sorted(Comparator.comparing(ScenarioLab::getCompletedAt).reversed()).toList();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_ASSESSMENT_NOT_FOUND", "We couldn't find that assessment.");
    }
}
