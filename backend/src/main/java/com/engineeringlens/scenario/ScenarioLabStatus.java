package com.engineeringlens.scenario;

/**
 * GENERATING → ACTIVE → FINALIZING → COMPLETED, or FAILED / CANCELLED. Only the first three are "open":
 * an open lab is the user's workspace; every other status is history or nothing.
 */
public enum ScenarioLabStatus {
    GENERATING, ACTIVE, FINALIZING, COMPLETED, FAILED, CANCELLED;

    public boolean open() {
        return this == GENERATING || this == ACTIVE || this == FINALIZING;
    }
}
