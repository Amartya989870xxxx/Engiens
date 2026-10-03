package com.engineeringlens.analysis.deterministic;

import java.util.List;
import java.util.function.BiFunction;

import com.engineeringlens.analysis.common.ReviewDimension;

/** One deterministic check. Pure: same input, same signals. */
public interface AnalysisRule {

    String id();

    ReviewDimension category();

    String description();

    List<Signal> evaluate(AnalysisInput input);

    /** Defines a rule inline; the function receives the rule itself so it can stamp its id on signals. */
    static AnalysisRule of(String id, ReviewDimension category, String description,
            BiFunction<AnalysisRule, AnalysisInput, List<Signal>> check) {
        return new AnalysisRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ReviewDimension category() {
                return category;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<Signal> evaluate(AnalysisInput input) {
                return check.apply(this, input);
            }

            @Override
            public String toString() {
                return id;
            }
        };
    }
}
