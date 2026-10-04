package com.engineeringlens.scenario;

/**
 * The level a lab is pitched at. It changes the engineering depth of a scenario (what it asks you to reason
 * about), not its length. It's part of the task definition, like the level of an interview, and is separate
 * from the developer's own profile, which only shapes teaching.
 */
public enum Seniority {
    BEGINNER("Beginner"),
    SDE1("SDE1"),
    SDE2("SDE2"),
    SDE3("SDE3"),
    SENIOR_ARCHITECT("Senior Architect");

    private final String label;

    Seniority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
