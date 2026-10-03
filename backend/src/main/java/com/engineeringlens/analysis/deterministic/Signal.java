package com.engineeringlens.analysis.deterministic;

import java.util.LinkedHashMap;
import java.util.Map;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.ReviewDimension;

/**
 * A machine-detected observation with its evidence, e.g. "No CI workflow was detected." A signal is
 * not a review finding: deciding whether it matters, and why, is the reviewer's job in a later phase.
 *
 * @param file the file the signal is about, if any
 * @param line 1-based line in that file, if known
 */
public record Signal(String ruleId, ReviewDimension category, Severity severity, Confidence confidence, String message,
        Map<String, Object> evidence, String file, Integer line, boolean machineDetectable) {

    public static Signal of(AnalysisRule rule, Severity severity, Confidence confidence, String message,
            Map<String, Object> evidence) {
        return new Signal(rule.id(), rule.category(), severity, confidence, message, evidence, null, null, true);
    }

    public Signal at(String file, Integer line) {
        return new Signal(ruleId, category, severity, confidence, message, evidence, file, line, machineDetectable);
    }

    /** Ordered evidence map: evidence("count", 3, "paths", list). Keeps output stable across runs. */
    public static Map<String, Object> evidence(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
