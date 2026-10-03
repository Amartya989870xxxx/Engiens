package com.engineeringlens.analysis.context;

/** What a file appears to be, from its path alone. A file can have several roles. */
public enum FileRole {
    ENTRYPOINT("application entry point"),
    ROUTE_CONTROLLER("route or controller file"),
    SERVICE("service-layer file"),
    DATA_ACCESS("repository or data-access file"),
    MODEL("model or entity file"),
    SCHEMA_DTO("schema or DTO file"),
    MIGRATION("database migration"),
    TEST("automated test file"),
    TEST_CONFIG("test configuration"),
    CONFIG("configuration file"),
    DATABASE_CONFIG("database configuration"),
    TOOLING_CONFIG("build/lint tooling configuration"),
    ENV_TEMPLATE("environment variable template"),
    BUILD_MANIFEST("dependency/build manifest"),
    CONTAINER("container definition"),
    CI("CI workflow"),
    DEPLOYMENT("deployment configuration"),
    ERROR_HANDLING("error-handling code"),
    SECURITY("authentication or security code"),
    LOGGING("logging setup"),
    MIDDLEWARE("middleware"),
    UTILITY("shared utility"),
    UI_COMPONENT("UI component"),
    API_CLIENT("API client"),
    DOCUMENTATION("project documentation"),
    SOURCE("source file");

    private final String description;

    FileRole(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
