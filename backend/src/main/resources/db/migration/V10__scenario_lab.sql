-- Scenario Lab (Phase 5).
-- A lab is a stable snapshot: the repository at one commit, the review it came from (optional), the chosen
-- roles, seniority and size. Its row outlives the workspace: once COMPLETED it is the anchor of a permanent,
-- read-only assessment, and only GENERATING/ACTIVE/FINALIZING labs are ever served as a workspace.

CREATE TABLE scenario_labs (
    id                      UUID                     PRIMARY KEY,
    user_id                 UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repository_id           UUID                     NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    -- The completed review this lab was started from (NULL for a lab started directly from a GitHub link).
    -- It points at the review document itself, so only a review that actually completed can be referenced.
    review_id               UUID                     REFERENCES reviews (review_run_id) ON DELETE SET NULL,
    analysis_run_id         UUID                     NOT NULL REFERENCES analysis_runs (id) ON DELETE CASCADE,
    commit_sha              VARCHAR(40)              NOT NULL,
    roles                   VARCHAR(500)             NOT NULL,
    seniority               VARCHAR(30)              NOT NULL,
    scenario_count          INTEGER                  NOT NULL,
    scenario_schema_version INTEGER                  NOT NULL,
    generator_version       INTEGER                  NOT NULL,
    status                  VARCHAR(20)              NOT NULL,
    -- Equals user_id while the lab is the user's workspace, NULL otherwise. A plain UNIQUE constraint then
    -- means "at most one open lab per user" (NULLs never clash), which works on PostgreSQL and on H2.
    active_user_id          UUID                     UNIQUE,
    scenarios_ready         INTEGER                  NOT NULL,
    error_code              VARCHAR(60),
    error_message           VARCHAR(300),
    version                 BIGINT                   NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    generated_at            TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_scenario_labs_user ON scenario_labs (user_id, created_at);
CREATE INDEX idx_scenario_labs_repository ON scenario_labs (repository_id, created_at);
CREATE INDEX idx_scenario_labs_review ON scenario_labs (review_id);

-- One validated scenario of a lab. Public parts (statement, evidence, rubric) and private parts (hidden checks,
-- reference solution) are separate columns so the API can never return the private ones by accident.
-- Workspaces are small extracts adapted from the repository (capped by the generator), never whole files.
CREATE TABLE scenarios (
    id                   UUID                     PRIMARY KEY,
    lab_id               UUID                     NOT NULL REFERENCES scenario_labs (id) ON DELETE CASCADE,
    position             INTEGER                  NOT NULL,
    schema_version       INTEGER                  NOT NULL,
    role                 VARCHAR(40)              NOT NULL,
    seniority            VARCHAR(30)              NOT NULL,
    category             VARCHAR(40)              NOT NULL,
    difficulty           VARCHAR(20)              NOT NULL,
    execution_capability VARCHAR(20)              NOT NULL,
    language             VARCHAR(20),
    title                VARCHAR(200)             NOT NULL,
    scenario_json        VARCHAR(200000)          NOT NULL,
    workspace_json       VARCHAR(200000),
    harness_json         VARCHAR(200000),
    reference_json       VARCHAR(200000)          NOT NULL,
    validation_json      VARCHAR(20000),
    -- The user's work in progress (autosaved while the lab is open). Submitted work is copied into an attempt.
    draft_mode           VARCHAR(20)              NOT NULL,
    draft_files_json     VARCHAR(200000),
    draft_approach       VARCHAR(20000),
    draft_updated_at     TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_scenarios_lab_position UNIQUE (lab_id, position)
);

-- A submitted scenario: permanent and immutable once written (only its evaluation is filled in, once).
-- It copies everything needed to read it later, so it never depends on the workspace still being open.
CREATE TABLE scenario_attempts (
    id                      UUID                     PRIMARY KEY,
    user_id                 UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repository_id           UUID                     NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    review_id               UUID                     REFERENCES reviews (review_run_id) ON DELETE SET NULL,
    lab_id                  UUID                     NOT NULL REFERENCES scenario_labs (id) ON DELETE CASCADE,
    -- UNIQUE: a scenario can be submitted once, so a double click or a second tab can't create two attempts.
    scenario_id             UUID                     NOT NULL UNIQUE REFERENCES scenarios (id) ON DELETE CASCADE,
    scenario_schema_version INTEGER                  NOT NULL,
    commit_sha              VARCHAR(40)              NOT NULL,
    role                    VARCHAR(40)              NOT NULL,
    seniority               VARCHAR(30)              NOT NULL,
    category                VARCHAR(40)              NOT NULL,
    mode                    VARCHAR(20)              NOT NULL,
    submitted_files_json    VARCHAR(200000),
    submitted_approach      VARCHAR(20000),
    run_result_json         VARCHAR(100000),
    evaluation_status       VARCHAR(20)              NOT NULL,
    evaluation_json         VARCHAR(200000),
    provider                VARCHAR(40),
    model                   VARCHAR(150),
    error_code              VARCHAR(60),
    error_message           VARCHAR(300),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    evaluated_at            TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_scenario_attempts_user ON scenario_attempts (user_id, created_at);
CREATE INDEX idx_scenario_attempts_repository ON scenario_attempts (repository_id, created_at);
CREATE INDEX idx_scenario_attempts_review ON scenario_attempts (review_id);
CREATE INDEX idx_scenario_attempts_lab ON scenario_attempts (lab_id);

-- The lab's final, personalised assessment, written once when the lab completes.
CREATE TABLE scenario_lab_assessments (
    lab_id          UUID                     PRIMARY KEY REFERENCES scenario_labs (id) ON DELETE CASCADE,
    assessment_json VARCHAR(200000)          NOT NULL,
    provider        VARCHAR(40),
    model           VARCHAR(150),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
