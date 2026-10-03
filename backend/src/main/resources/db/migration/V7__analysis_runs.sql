-- One attempt to prepare an imported repository for review (profile → signals → context).
-- Runs execute synchronously for now; the status model is ready for background execution later.
CREATE TABLE analysis_runs (
    id                     UUID                     PRIMARY KEY,
    repository_id          UUID                     NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    user_id                UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                 VARCHAR(20)              NOT NULL,
    commit_sha             VARCHAR(40),
    profile_schema_version INTEGER                  NOT NULL,
    rules_version          INTEGER                  NOT NULL,
    context_schema_version INTEGER                  NOT NULL,
    config_fingerprint     VARCHAR(300)             NOT NULL,
    files_fetched          INTEGER,
    bytes_fetched          BIGINT,
    signal_count           INTEGER,
    context_file_count     INTEGER,
    context_bytes          BIGINT,
    duration_ms            BIGINT,
    failure_reason         VARCHAR(300),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at             TIMESTAMP WITH TIME ZONE,
    completed_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_analysis_runs_repository ON analysis_runs (repository_id, created_at);

-- The run's outputs as JSON. No source code is stored: the manifest records paths, reasons, sizes and
-- content hashes, and contents can be re-fetched from the pinned commit and verified against the hashes.
CREATE TABLE analysis_artifacts (
    run_id                UUID              PRIMARY KEY REFERENCES analysis_runs (id) ON DELETE CASCADE,
    profile_json          VARCHAR(1000000)  NOT NULL,
    signals_json          VARCHAR(1000000)  NOT NULL,
    context_manifest_json VARCHAR(1000000)  NOT NULL
);
