-- A user's link to their Engineering Lens GitHub App installation.
-- Only the installation id is stored; access tokens are minted per request and never persisted.
CREATE TABLE github_connections (
    user_id         UUID                     PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    installation_id BIGINT                   NOT NULL,
    github_login    VARCHAR(39)              NOT NULL,
    connected_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Single-use values that tie GitHub's redirect back to the user who started connecting.
CREATE TABLE github_connect_states (
    state      VARCHAR(64)              PRIMARY KEY,
    user_id    UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
