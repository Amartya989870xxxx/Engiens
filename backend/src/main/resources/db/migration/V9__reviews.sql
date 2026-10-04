-- One AI review attempt of a prepared repository, and its validated result.
-- No prompts, raw provider responses or source code are stored; only the validated review JSON.
CREATE TABLE review_runs (
    id                     UUID                     PRIMARY KEY,
    repository_id          UUID                     NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    analysis_run_id        UUID                     NOT NULL REFERENCES analysis_runs (id) ON DELETE CASCADE,
    user_id                UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                 VARCHAR(20)              NOT NULL,
    review_schema_version  INTEGER                  NOT NULL,
    rubric_version         INTEGER                  NOT NULL,
    context_schema_version INTEGER                  NOT NULL,
    commit_sha             VARCHAR(40),
    provider               VARCHAR(40),
    model                  VARCHAR(150),
    fallback_used          BOOLEAN                  NOT NULL,
    fallback_reason        VARCHAR(80),
    attempt_count          INTEGER,
    input_tokens           INTEGER,
    output_tokens          INTEGER,
    error_code             VARCHAR(60),
    error_message          VARCHAR(300),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at             TIMESTAMP WITH TIME ZONE,
    completed_at           TIMESTAMP WITH TIME ZONE,
    duration_ms            BIGINT
);

CREATE INDEX idx_review_runs_repository ON review_runs (repository_id, created_at);
CREATE INDEX idx_review_runs_user ON review_runs (user_id, created_at);

CREATE TABLE reviews (
    review_run_id UUID                     PRIMARY KEY REFERENCES review_runs (id) ON DELETE CASCADE,
    review_json   VARCHAR(1000000)         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
