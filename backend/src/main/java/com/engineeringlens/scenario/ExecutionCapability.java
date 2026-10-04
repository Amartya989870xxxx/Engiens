package com.engineeringlens.scenario;

/**
 * What a scenario supports, fixed when it is generated. A CODE scenario has a validated, executable harness
 * and opens in code mode (approach mode is always available too); an APPROACH_ONLY scenario is about
 * reasoning (architecture, cloud, networks) or couldn't be made executable, so it only accepts an approach.
 */
public enum ExecutionCapability {
    CODE, APPROACH_ONLY
}
