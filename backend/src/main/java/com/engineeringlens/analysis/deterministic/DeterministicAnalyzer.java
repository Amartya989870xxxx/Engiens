package com.engineeringlens.analysis.deterministic;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Runs every registered rule, in a fixed order, over one analysis input. */
@Component
public class DeterministicAnalyzer {

    public static final int SCHEMA_VERSION = 1;
    /** Bump whenever a rule is added, removed or changes behaviour. */
    public static final int RULES_VERSION = 1;

    /** The registry: one ordered list, grouped by area. Order is part of the output, so it is fixed. */
    public static final List<AnalysisRule> RULES = List.copyOf(concat(
            TestingRules.ALL, ProductionReadinessRules.ALL, SecurityRules.ALL, StructureRules.ALL, PersistenceRules.ALL));

    public DeterministicAnalysis analyze(AnalysisInput input) {
        List<Signal> signals = new ArrayList<>();
        for (AnalysisRule rule : RULES) {
            signals.addAll(rule.evaluate(input));
        }
        return new DeterministicAnalysis(SCHEMA_VERSION, RULES_VERSION, RULES.stream().map(AnalysisRule::id).toList(), signals);
    }

    @SafeVarargs
    private static List<AnalysisRule> concat(List<AnalysisRule>... groups) {
        List<AnalysisRule> all = new ArrayList<>();
        for (List<AnalysisRule> g : groups) {
            all.addAll(g);
        }
        return all;
    }
}
