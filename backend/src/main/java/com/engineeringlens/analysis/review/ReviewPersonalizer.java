package com.engineeringlens.analysis.review;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.context.DeveloperProfile;
import com.engineeringlens.analysis.review.model.ReviewDocument.Personalization;

/**
 * Decides how explanations are pitched. It changes teaching, never technical judgement: the audience
 * only shapes wording, advice and the learning plan, while assessments must rest on evidence alone.
 */
@Component
public class ReviewPersonalizer {

    public static final String FOUNDATION = "FOUNDATION";
    public static final String INTERMEDIATE = "INTERMEDIATE";
    public static final String EXPERIENCED = "EXPERIENCED";

    public Personalization personalize(DeveloperProfile p) {
        if (p == null || p.level() == null) {
            return new Personalization(INTERMEDIATE, "No engineering profile, so explanations assume an intermediate developer.");
        }
        return switch (p.level()) {
            case "SCHOOL_STUDENT" -> new Personalization(FOUNDATION, "School student");
            case "UNDERGRADUATE" -> p.classYear() != null && p.classYear() >= 3
                    ? new Personalization(INTERMEDIATE, "Undergraduate, year " + p.classYear())
                    : new Personalization(FOUNDATION, "Undergraduate" + (p.classYear() == null ? "" : ", year " + p.classYear()));
            case "GRADUATE" -> new Personalization(INTERMEDIATE, "Graduate student");
            case "PROFESSIONAL" -> p.workExperience() == null || p.workExperience().equals("UNDER_ONE_YEAR")
                    || p.workExperience().equals("ONE_TO_TWO_YEARS")
                    ? new Personalization(INTERMEDIATE, "Early-career professional")
                    : new Personalization(EXPERIENCED, "Professional, " + p.workExperience().toLowerCase().replace('_', ' '));
            default -> new Personalization(INTERMEDIATE, "Unrecognised level");
        };
    }

    /** How the reviewer should write for this audience. */
    public String guidance(Personalization p) {
        return switch (p.audience()) {
            case FOUNDATION -> """
                    Audience: an early learner. Acknowledge what already works before suggesting changes. Explain terms
                    the first time you use them (e.g. "service layer", "transaction"). Recommend one concrete next step at a
                    time. Keep advanced topics (distributed systems, deep performance tuning) in advancedTopics only.""";
            case EXPERIENCED -> """
                    Audience: an experienced engineer. Be direct and precise; skip basic definitions. Focus on trade-offs,
                    failure modes and design alternatives. Advanced topics may appear in main recommendations when relevant.""";
            default -> """
                    Audience: a developer with working experience. Use standard engineering vocabulary with brief context.
                    Connect each recommendation to the evidence and to the next level of practice.""";
        };
    }
}
