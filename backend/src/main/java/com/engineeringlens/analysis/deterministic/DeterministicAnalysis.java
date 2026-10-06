package com.engineeringlens.analysis.deterministic;

import java.util.List;

/**
 * The output of the deterministic rules. rulesRun records exactly which rules produced these signals, so a stored
 * analysis can always be explained and compared with a later one.
 */
public record DeterministicAnalysis(int analysisSchemaVersion, int rulesVersion, List<String> rulesRun, List<Signal> signals) {
}
