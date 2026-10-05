package com.engineeringlens.scenario.workspace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioAttempt;
import com.engineeringlens.scenario.ScenarioAttemptRepository;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLabRepository;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioRepository;
import com.engineeringlens.scenario.WorkMode;
import com.engineeringlens.scenario.evaluation.EvaluationWorker;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.execution.ScenarioExecutionService;
import com.engineeringlens.scenario.execution.WorkspaceFile;
import com.engineeringlens.scenario.lab.ScenarioLabService;
import com.engineeringlens.scenario.model.ScenarioDocument;
import com.engineeringlens.scenario.model.ScenarioHarness;
import com.engineeringlens.scenario.model.ScenarioWorkspace;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The open lab's workspace: read a scenario, autosave, Run against the hidden checks, and Submit. Every
 * operation checks ownership and that the lab is still open; a submitted scenario can't be changed or
 * submitted again (the database enforces it too). Read-only workspace files always run as generated.
 */
@Service
public class ScenarioWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioWorkspaceService.class);
    private static final Duration SUBMIT_SLOT_WAIT = Duration.ofSeconds(60);
    private static final int MIN_APPROACH_CHARS = 30;
    private static final TypeReference<List<FileContent>> FILES = new TypeReference<>() {
    };

    private final ScenarioLabService labService;
    private final ScenarioLabRepository labs;
    private final ScenarioRepository scenarios;
    private final ScenarioAttemptRepository attempts;
    private final ImportedRepoRepository repositories;
    private final ScenarioExecutionService execution;
    private final EvaluationWorker evaluation;
    private final Executor executor;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    /** Users with a Run or Submit executing: one at a time per user, so a double click can't start two sandboxes. */
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();

    public ScenarioWorkspaceService(ScenarioLabService labService, ScenarioLabRepository labs, ScenarioRepository scenarios,
            ScenarioAttemptRepository attempts, ImportedRepoRepository repositories, ScenarioExecutionService execution,
            EvaluationWorker evaluation, @Qualifier("scenarioExecutor") Executor executor, ObjectMapper json, PlatformTransactionManager tm) {
        this.labService = labService;
        this.labs = labs;
        this.scenarios = scenarios;
        this.attempts = attempts;
        this.repositories = repositories;
        this.execution = execution;
        this.evaluation = evaluation;
        this.executor = executor;
        this.json = json;
        this.transaction = new TransactionTemplate(tm);
    }

    public ScenarioDetailResponse detail(UUID userId, UUID labId, UUID scenarioId) {
        ScenarioLab lab = labService.owned(userId, labId);
        if (lab.getStatus() != ScenarioLabStatus.ACTIVE && lab.getStatus() != ScenarioLabStatus.FINALIZING) {
            throw notActive(); // a finished lab is read as an assessment, never reopened as a workspace
        }
        Scenario s = scenario(lab, scenarioId);
        ScenarioAttempt attempt = attempts.findByScenarioId(s.getId()).orElse(null);
        Map<String, String> draft = new LinkedHashMap<>();
        if (s.getDraftFilesJson() != null) {
            json.readValue(s.getDraftFilesJson(), FILES).forEach(f -> draft.put(f.path(), f.content()));
        }
        List<ScenarioDetailResponse.FileView> files = s.getWorkspaceJson() == null ? List.of()
                : workspace(s).files().stream().map(f -> new ScenarioDetailResponse.FileView(f.path(), f.editable(), f.content(),
                        f.editable() ? draft.getOrDefault(f.path(), f.content()) : f.content())).toList();
        var repo = repositories.findById(lab.getRepositoryId()).orElse(null);
        return new ScenarioDetailResponse(s.getId(), lab.getId(), s.getPosition(), lab.getScenarioCount(),
                repo == null ? null : repo.getGithubRepoName(), lab.getCommitSha(), s.getTitle(), s.getRole(), s.getSeniority(),
                s.getCategory(), s.getDifficulty(), s.getExecutionCapability(), s.getLanguage(),
                json.readValue(s.getScenarioJson(), ScenarioDocument.class), files, s.getDraftMode(), s.getDraftApproach(),
                s.getDraftUpdatedAt(), attempt != null, attempt == null ? null : attempt.getEvaluationStatus());
    }

    /** Autosave. Only the editable files are kept; the approach and the mode are kept independently. */
    public void saveDraft(UUID userId, UUID labId, UUID scenarioId, SaveDraftRequest request) {
        ScenarioLab lab = activeLab(userId, labId);
        Scenario s = unsubmitted(lab, scenarioId);
        if (request.mode() == WorkMode.CODE && s.getExecutionCapability() != ExecutionCapability.CODE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODE", "This scenario is answered with an approach.");
        }
        String files = request.files() == null || s.getWorkspaceJson() == null ? null : json.writeValueAsString(editable(s, request.files()));
        s.saveDraft(request.mode(), files, request.approach());
        scenarios.save(s);
    }

    /** Objective feedback: the current files against the hidden checks. Not an evaluation, and nothing is submitted. */
    public RunResult run(UUID userId, UUID labId, UUID scenarioId, RunRequest request) {
        ScenarioLab lab = activeLab(userId, labId);
        Scenario s = unsubmitted(lab, scenarioId);
        if (s.getExecutionCapability() != ExecutionCapability.CODE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODE", "This scenario is answered with an approach, so there is nothing to run.");
        }
        List<FileContent> edited = editable(s, request.files());
        s.saveDraft(WorkMode.CODE, json.writeValueAsString(edited), null); // what was run survives a refresh
        scenarios.save(s);
        return execute(userId, s, edited, null);
    }

    /**
     * The final answer: runs the code once more for objective evidence, then freezes code, approach and
     * results into a permanent attempt and starts its evaluation in the background.
     */
    public AttemptStatusResponse submit(UUID userId, UUID labId, UUID scenarioId, SubmitRequest request) {
        ScenarioLab lab = activeLab(userId, labId);
        Scenario s = unsubmitted(lab, scenarioId);
        boolean code = s.getExecutionCapability() == ExecutionCapability.CODE;
        if (request.mode() == WorkMode.CODE && !code) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODE", "This scenario is answered with an approach.");
        }
        String approach = request.approach() == null || request.approach().isBlank() ? null : request.approach().strip();
        if (request.mode() == WorkMode.APPROACH && (approach == null || approach.length() < MIN_APPROACH_CHARS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPROACH_REQUIRED",
                    "Explain your approach in a few sentences before submitting.");
        }
        List<FileContent> edited = null;
        RunResult run = null;
        if (code) {
            edited = editable(s, request.files() != null ? request.files()
                    : s.getDraftFilesJson() == null ? List.of() : json.readValue(s.getDraftFilesJson(), FILES));
            try {
                run = execute(userId, s, edited, SUBMIT_SLOT_WAIT);
            } catch (ApiException e) {
                if (!e.getCode().startsWith("SCENARIO_EXECUTION_")) {
                    throw e;
                }
                log.warn("Submitting without a final run: lab={} scenario={} reason={}", labId, scenarioId, e.getCode());
            }
        }
        String filesJson = edited == null ? null : json.writeValueAsString(edited);
        String runJson = run == null ? null : json.writeValueAsString(run);
        ScenarioAttempt attempt;
        try {
            attempt = transaction.execute(tx -> {
                ScenarioLab current = labs.findById(labId).orElseThrow();
                if (current.getStatus() != ScenarioLabStatus.ACTIVE) {
                    throw notActive();
                }
                Scenario fresh = scenarios.findById(s.getId()).orElseThrow();
                fresh.saveDraft(request.mode(), filesJson, approach);
                scenarios.save(fresh);
                return attempts.saveAndFlush(new ScenarioAttempt(current, fresh, request.mode(), filesJson, approach, runJson));
            });
        } catch (DataIntegrityViolationException e) {
            throw alreadySubmitted(); // a second tab or a double click got there first
        }
        log.info("Scenario submitted: lab={} scenario={} attempt={} mode={} run={}", labId, scenarioId, attempt.getId(), request.mode(),
                run == null ? "none" : run.status() + " " + run.passed() + "/" + run.total());
        dispatch(attempt.getId());
        return status(attempts.findById(attempt.getId()).orElse(attempt));
    }

    /** A failed evaluation (AI unavailable, interrupted) can be tried again; the submission is untouched. */
    public AttemptStatusResponse retryEvaluation(UUID userId, UUID labId, UUID scenarioId) {
        ScenarioLab lab = activeLab(userId, labId);
        Scenario s = scenario(lab, scenarioId);
        ScenarioAttempt attempt = attempts.findByScenarioId(s.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_ATTEMPT_NOT_FOUND", "This scenario hasn't been submitted."));
        if (attempt.getEvaluationStatus() != EvaluationStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "EVALUATION_NOT_FAILED", "This evaluation is already done or in progress.");
        }
        attempt.retryEvaluation();
        attempts.save(attempt);
        dispatch(attempt.getId());
        return status(attempts.findById(attempt.getId()).orElse(attempt));
    }

    /** Finish now with the scenarios submitted so far (all of them evaluated); the rest aren't assessed. */
    public void finishEarly(UUID userId, UUID labId) {
        ScenarioLab lab = labService.owned(userId, labId);
        evaluation.finishEarly(lab.getId());
    }

    /** Writing the final assessment failed (no AI model, restart): every submission is safe, so try again. */
    public void retryFinalization(UUID userId, UUID labId) {
        ScenarioLab lab = labService.owned(userId, labId);
        if (lab.getStatus() != ScenarioLabStatus.FINALIZING || lab.getErrorCode() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_LAB_NOT_FINALIZING", "This lab isn't waiting to be finalised.");
        }
        evaluation.retryFinalization(labId);
    }

    private RunResult execute(UUID userId, Scenario s, List<FileContent> edited, Duration slotWait) {
        if (!executing.add(userId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SCENARIO_RUN_IN_PROGRESS", "Your previous run is still going. Wait for it to finish.");
        }
        try {
            ScenarioHarness harness = json.readValue(s.getHarnessJson(), ScenarioHarness.class);
            List<WorkspaceFile> files = merged(s, edited);
            return slotWait == null ? execution.run(s.getLanguage(), files, harness.checksSource())
                    : execution.run(s.getLanguage(), files, harness.checksSource(), slotWait);
        } finally {
            executing.remove(userId);
        }
    }

    /** The starter workspace with the user's versions of its editable files. */
    private List<WorkspaceFile> merged(Scenario s, List<FileContent> edited) {
        Map<String, String> byPath = new LinkedHashMap<>();
        edited.forEach(f -> byPath.put(f.path(), f.content()));
        return workspace(s).files().stream()
                .map(f -> new WorkspaceFile(f.path(), f.editable() && byPath.containsKey(f.path()) ? byPath.get(f.path()) : f.content()))
                .toList();
    }

    /** Keeps only the scenario's editable files; anything else the browser sends is rejected. */
    private List<FileContent> editable(Scenario s, List<FileContent> files) {
        Map<String, Boolean> editable = new LinkedHashMap<>();
        workspace(s).files().forEach(f -> editable.put(f.path(), f.editable()));
        List<FileContent> kept = new ArrayList<>();
        for (FileContent f : files) {
            Boolean e = editable.get(f.path());
            if (e == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE", "Unknown file: " + f.path());
            }
            if (e) {
                kept.add(f);
            }
        }
        return kept;
    }

    private void dispatch(UUID attemptId) {
        try {
            executor.execute(() -> evaluation.evaluate(attemptId));
        } catch (TaskRejectedException e) {
            evaluation.markFailed(attemptId, "BUSY", "Engiens is evaluating many submissions right now. Try the evaluation again in a minute.");
        }
    }

    AttemptStatusResponse status(ScenarioAttempt a) {
        return new AttemptStatusResponse(a.getId(), a.getScenarioId(), a.getMode(),
                a.getRunResultJson() == null ? null : json.readValue(a.getRunResultJson(), RunResult.class), a.getEvaluationStatus(),
                a.getErrorCode(), a.getErrorMessage(), a.getCreatedAt());
    }

    private ScenarioLab activeLab(UUID userId, UUID labId) {
        ScenarioLab lab = labService.owned(userId, labId);
        if (lab.getStatus() != ScenarioLabStatus.ACTIVE) {
            throw notActive();
        }
        return lab;
    }

    private Scenario scenario(ScenarioLab lab, UUID scenarioId) {
        return scenarios.findByIdAndLabId(scenarioId, lab.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_NOT_FOUND", "We couldn't find that scenario."));
    }

    private Scenario unsubmitted(ScenarioLab lab, UUID scenarioId) {
        Scenario s = scenario(lab, scenarioId);
        if (attempts.findByScenarioId(s.getId()).isPresent()) {
            throw alreadySubmitted();
        }
        return s;
    }

    private ScenarioWorkspace workspace(Scenario s) {
        return json.readValue(s.getWorkspaceJson(), ScenarioWorkspace.class);
    }

    private static ApiException notActive() {
        return new ApiException(HttpStatus.CONFLICT, "SCENARIO_LAB_NOT_ACTIVE", "This lab is no longer open.");
    }

    private static ApiException alreadySubmitted() {
        return new ApiException(HttpStatus.CONFLICT, "SCENARIO_SUBMISSION_ALREADY_COMPLETED", "This scenario has already been submitted.");
    }
}
