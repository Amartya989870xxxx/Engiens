CREATE TABLE users (
    id            UUID                     PRIMARY KEY,
    email         VARCHAR(254)             NOT NULL UNIQUE,
    password_hash VARCHAR(100)             NOT NULL,
    name          VARCHAR(100)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_profiles (
    user_id           UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    level             VARCHAR(20)  NOT NULL,
    class_year        INTEGER,
    languages         VARCHAR(500) NOT NULL,
    frameworks        VARCHAR(500) NOT NULL,
    databases         VARCHAR(500) NOT NULL,
    experience_areas  VARCHAR(500) NOT NULL,
    previous_projects VARCHAR(2000),
    goals             VARCHAR(1000),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
