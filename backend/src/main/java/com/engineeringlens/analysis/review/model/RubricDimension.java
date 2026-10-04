package com.engineeringlens.analysis.review.model;

import java.util.List;

/**
 * The review rubric: 16 dimensions with the questions a reviewer asks. Defined once and used by the
 * prompt, the validator and (by id) the UI. Bump {@link #RUBRIC_VERSION} when it changes.
 */
public enum RubricDimension {
    CORRECTNESS_AND_FEATURE_IMPLEMENTATION("Correctness & Feature Implementation", List.of(
            "Does the implementation appear functionally correct?", "Are important business rules represented?",
            "Are edge cases handled?", "Are important workflows incomplete?", "Are invalid states possible?")),
    ARCHITECTURE_AND_MODULARITY("Architecture & Modularity", List.of(
            "Are responsibilities separated?", "Are coupling and cohesion reasonable?", "Are boundaries clear?",
            "Is business logic placed appropriately?", "Are abstractions useful or excessive?")),
    SYSTEM_DESIGN_AND_SCALABILITY("System Design & Scalability", List.of(
            "What happens as traffic grows?", "What happens as data grows?", "Where are potential bottlenecks?",
            "Are synchronous operations likely to become expensive?", "Are stateful assumptions visible?")),
    BACKEND_ENGINEERING("Backend Engineering", List.of(
            "Is backend business logic organised?", "Are transactions appropriate?",
            "Are domain decisions separated from transport?", "Are services/repositories/controllers used sensibly?")),
    API_DESIGN_AND_INTEGRATION("API Design & Integration", List.of(
            "Are API boundaries and request/response contracts clear?", "Are validation and error responses consistent?",
            "Are HTTP semantics appropriate where observable?", "Are external APIs handled safely?", "Is idempotency needed?")),
    DATA_AND_PERSISTENCE("Data & Persistence", List.of(
            "Is the schema/model sensible?", "Are queries likely to scale; would indexes matter?",
            "Are transactions appropriate?", "Are migrations present?", "Is persistence logic separated?")),
    CONCURRENCY_AND_CONSISTENCY("Concurrency & Consistency", List.of(
            "Could simultaneous operations conflict?", "Can duplicate operations occur?", "Are state transitions safe?",
            "Are race conditions plausible?", "Are locking/transaction concerns visible?")),
    PERFORMANCE_AND_EFFICIENCY("Performance & Efficiency", List.of(
            "Is work unnecessarily repeated?", "Are repeated database/API calls visible?", "Is unnecessary data fetched?",
            "Are caching opportunities obvious?", "Never invent runtime performance claims.")),
    ERROR_HANDLING_AND_RESILIENCE("Error Handling & Resilience", List.of(
            "Are expected failures handled and surfaced properly?", "Are retries safe; are timeouts present where useful?",
            "Does the system degrade gracefully?", "Are partial failures considered?")),
    SECURITY("Security", List.of(
            "Is authentication present where required and authorisation enforced?", "Are secrets protected?",
            "Is input validated?", "Are obvious injection patterns visible?", "Is sensitive data handled safely?",
            "This is not a complete security audit.")),
    TESTING_AND_QUALITY_ASSURANCE("Testing & Quality Assurance", List.of(
            "Are tests present, and do they cover important workflows and edge cases?",
            "Are integration tests useful and regression paths protected?",
            "\"Tests detected\" is not \"good coverage\"; coverage is never measured.")),
    CODE_QUALITY_AND_MAINTAINABILITY("Code Quality & Maintainability", List.of(
            "Is code readable with meaningful names?", "Is complexity reasonable; is there duplication?",
            "Are abstractions useful?", "Would future modification be difficult?")),
    PRODUCTION_READINESS_AND_OPERATIONS("Production Readiness & Operations", List.of(
            "Is logging present and configuration separated?", "Is deployment considered; are health checks present?",
            "Is observability visible; is CI/CD present?", "Is failure diagnosis practical?")),
    DEPENDENCIES_AND_EXTERNAL_SERVICES("Dependencies & External Services", List.of(
            "Are external dependencies appropriate and used intentionally?", "Is coupling to third-party services reasonable?",
            "Are their failures handled; are external APIs abstracted?", "This is not a vulnerability scan.")),
    DOCUMENTATION_AND_DEVELOPER_EXPERIENCE("Documentation & Developer Experience", List.of(
            "Can another developer understand setup?", "Is the README useful?",
            "Are important architectural assumptions documented?", "Is the project easy to run and understand?")),
    FRONTEND_CLIENT_ENGINEERING("Frontend & Client Engineering", List.of(
            "Only when a frontend/client exists.", "Is state handled appropriately; are API calls organised?",
            "Are loading/error/empty states considered?", "Is UI logic separated sensibly?",
            "Are route/auth boundaries reasonable?"));

    public static final int RUBRIC_VERSION = 1;

    private final String displayName;
    private final List<String> questions;

    RubricDimension(String displayName, List<String> questions) {
        this.displayName = displayName;
        this.questions = questions;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> questions() {
        return questions;
    }
}
