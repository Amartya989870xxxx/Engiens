-- A GitHub repository a user imported into Engiens, plus its file inventory (paths and sizes only,
-- never file contents). Analysis results will hang off these rows in a later phase.
CREATE TABLE repositories (
    id                  UUID                     PRIMARY KEY,
    user_id             UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    github_owner        VARCHAR(39)              NOT NULL,
    github_repo_name    VARCHAR(100)             NOT NULL,
    github_url          VARCHAR(300)             NOT NULL,
    description         VARCHAR(1000),
    default_branch      VARCHAR(255)             NOT NULL,
    primary_language    VARCHAR(50),
    visibility          VARCHAR(10)              NOT NULL,
    stars               INTEGER                  NOT NULL,
    forks               INTEGER                  NOT NULL,
    file_count          INTEGER                  NOT NULL,
    relevant_file_count INTEGER                  NOT NULL,
    ignored_file_count  INTEGER                  NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    failure_reason      VARCHAR(300),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Importing the same repository twice must reuse the existing row, never create a second one.
    CONSTRAINT uq_repositories_user_repo UNIQUE (user_id, github_owner, github_repo_name)
);

CREATE INDEX idx_repositories_user_updated ON repositories (user_id, updated_at);

CREATE TABLE repository_files (
    id            UUID          PRIMARY KEY,
    repository_id UUID          NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    path          VARCHAR(2048) NOT NULL,
    file_name     VARCHAR(255)  NOT NULL,
    extension     VARCHAR(20),
    language      VARCHAR(50),
    size_bytes    BIGINT        NOT NULL,
    ignored       BOOLEAN       NOT NULL,
    ignore_reason VARCHAR(100)
);

CREATE INDEX idx_repository_files_repository ON repository_files (repository_id);
