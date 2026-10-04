package com.engineeringlens.analysis.review.model;

/** Fixed value sets of the review schema. Anything else in model output is rejected. */
public final class ReviewEnums {

    private ReviewEnums() {
    }

    /** Five levels instead of a single score: Engiens teaches judgement, it doesn't grade people. */
    public enum Assessment {
        STRONG,
        SOLID,
        DEVELOPING,
        NEEDS_ATTENTION,
        NOT_ASSESSABLE
    }

    public enum Applicability {
        APPLICABLE,
        NOT_APPLICABLE
    }

    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum TradeoffAssessment {
        STRONG,
        REASONABLE,
        CONTEXT_DEPENDENT,
        QUESTIONABLE
    }

    public enum Difficulty {
        LOW,
        MEDIUM,
        HIGH
    }
}
