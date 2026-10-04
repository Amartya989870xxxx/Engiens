-- Per-model AI health, so cooldowns survive restarts. No keys, prompts or responses are stored here;
-- last_failure_message is a sanitised one-line description.
CREATE TABLE ai_model_health (
    id                   VARCHAR(200)             PRIMARY KEY,
    provider             VARCHAR(40)              NOT NULL,
    model                VARCHAR(150)             NOT NULL,
    state                VARCHAR(30)              NOT NULL,
    last_failure_type    VARCHAR(40),
    last_failure_message VARCHAR(300),
    failure_count        INTEGER                  NOT NULL,
    cooldown_until       TIMESTAMP WITH TIME ZONE,
    last_failure_at      TIMESTAMP WITH TIME ZONE,
    last_success_at      TIMESTAMP WITH TIME ZONE,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
