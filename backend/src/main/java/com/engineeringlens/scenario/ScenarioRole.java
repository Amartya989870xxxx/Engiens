package com.engineeringlens.scenario;

/**
 * The engineering role (domain) a scenario targets. Independent of seniority. Stored by name, so adding a
 * role later is one new constant and no schema change.
 */
public enum ScenarioRole {
    FRONTEND_ENGINEER("Frontend Engineer"),
    BACKEND_ENGINEER("Backend Engineer"),
    FULL_STACK_ENGINEER("Full-Stack Engineer"),
    DEVOPS_ENGINEER("DevOps Engineer"),
    INFRASTRUCTURE_ENGINEER("Infrastructure Engineer"),
    CLOUD_ENGINEER("Cloud Engineer"),
    NETWORK_ENGINEER("Network Engineer"),
    AI_ENGINEER("AI Engineer"),
    ML_ENGINEER("ML Engineer"),
    MLOPS_ENGINEER("MLOps Engineer"),
    AI_ML_ENGINEER("AI/ML Engineer"),
    AI_RESEARCHER("AI Researcher"),
    SOFTWARE_ARCHITECT("Software Architect"),
    /** "All / Broad Engineering": chosen on its own, never combined with specific roles. */
    BROAD_ENGINEERING("Broad Engineering");

    private final String label;

    ScenarioRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
