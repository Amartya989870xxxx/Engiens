package com.engineeringlens.scenario;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One validated scenario of a lab. The JSON columns hold validated, versioned documents, never raw model
 * output. Public parts (statement, evidence, rubric, starter workspace) and private parts (hidden checks,
 * reference) are kept apart so responses can only ever include the public ones. The draft fields are the
 * user's autosaved work while the lab is open.
 */
@Entity
@Table(name = "scenarios")
public class Scenario {

    /** Version of the scenario document stored in scenario_json/workspace_json/harness_json/reference_json. */
    public static final int SCHEMA_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lab_id", nullable = false)
    private UUID labId;

    @Column(nullable = false)
    private int position;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Seniority seniority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioDifficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_capability", nullable = false)
    private ExecutionCapability executionCapability;

    @Enumerated(EnumType.STRING)
    private ScenarioLanguage language;

    @Column(nullable = false)
    private String title;

    @Column(name = "scenario_json", nullable = false)
    private String scenarioJson;

    @Column(name = "workspace_json")
    private String workspaceJson;

    @Column(name = "harness_json")
    private String harnessJson;

    @Column(name = "reference_json", nullable = false)
    private String referenceJson;

    @Column(name = "validation_json")
    private String validationJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "draft_mode", nullable = false)
    private WorkMode draftMode;

    @Column(name = "draft_files_json")
    private String draftFilesJson;

    @Column(name = "draft_approach")
    private String draftApproach;

    @Column(name = "draft_updated_at")
    private Instant draftUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Scenario() {
    }

    /**
     * A CODE scenario opens in code mode; an APPROACH_ONLY one can only be answered in approach mode.
     * Code fields are required exactly when the scenario is executable.
     */
    public Scenario(UUID labId, int position, ScenarioRole role, Seniority seniority, ScenarioCategory category,
            ScenarioDifficulty difficulty, ExecutionCapability capability, ScenarioLanguage language, String title, String scenarioJson,
            String workspaceJson, String harnessJson, String referenceJson, String validationJson) {
        boolean code = capability == ExecutionCapability.CODE;
        if (code != (language != null && workspaceJson != null && harnessJson != null)) {
            throw new IllegalArgumentException("A CODE scenario needs a language, workspace and harness; APPROACH_ONLY has none");
        }
        this.labId = labId;
        this.position = position;
        this.schemaVersion = SCHEMA_VERSION;
        this.role = role;
        this.seniority = seniority;
        this.category = category;
        this.difficulty = difficulty;
        this.executionCapability = capability;
        this.language = language;
        this.title = title;
        this.scenarioJson = scenarioJson;
        this.workspaceJson = workspaceJson;
        this.harnessJson = harnessJson;
        this.referenceJson = referenceJson;
        this.validationJson = validationJson;
        this.draftMode = code ? WorkMode.CODE : WorkMode.APPROACH;
        this.createdAt = Instant.now();
    }

    /**
     * Autosave. Code and approach are kept independently, so switching modes never loses either.
     * Null leaves that part unchanged.
     */
    public void saveDraft(WorkMode mode, String filesJson, String approach) {
        if (mode == WorkMode.CODE && executionCapability != ExecutionCapability.CODE) {
            throw new IllegalArgumentException("This scenario can only be answered with an approach");
        }
        if (mode != null) {
            draftMode = mode;
        }
        if (filesJson != null) {
            draftFilesJson = filesJson;
        }
        if (approach != null) {
            draftApproach = approach;
        }
        draftUpdatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getLabId() { return labId; }
    public int getPosition() { return position; }
    public int getSchemaVersion() { return schemaVersion; }
    public ScenarioRole getRole() { return role; }
    public Seniority getSeniority() { return seniority; }
    public ScenarioCategory getCategory() { return category; }
    public ScenarioDifficulty getDifficulty() { return difficulty; }
    public ExecutionCapability getExecutionCapability() { return executionCapability; }
    public ScenarioLanguage getLanguage() { return language; }
    public String getTitle() { return title; }
    public String getScenarioJson() { return scenarioJson; }
    public String getWorkspaceJson() { return workspaceJson; }
    public String getHarnessJson() { return harnessJson; }
    public String getReferenceJson() { return referenceJson; }
    public String getValidationJson() { return validationJson; }
    public WorkMode getDraftMode() { return draftMode; }
    public String getDraftFilesJson() { return draftFilesJson; }
    public String getDraftApproach() { return draftApproach; }
    public Instant getDraftUpdatedAt() { return draftUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
