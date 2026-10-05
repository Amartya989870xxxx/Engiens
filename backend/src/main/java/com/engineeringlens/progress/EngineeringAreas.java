package com.engineeringlens.progress;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.scenario.ScenarioCategory;

/**
 * Where a Scenario Lab answer counts in Progress. The 16 review rubric dimensions are the only engineering areas;
 * every scenario category maps onto one of them, so review and Scenario Lab evidence can be read side by side.
 * Bump {@link #MAPPING_VERSION} when a mapping changes.
 */
public final class EngineeringAreas {

    public static final int MAPPING_VERSION = 1;

    private EngineeringAreas() {
    }

    private static final Map<ScenarioCategory, RubricDimension> AREAS = new EnumMap<>(Map.ofEntries(
            Map.entry(ScenarioCategory.PRODUCTION_BUG, RubricDimension.CORRECTNESS_AND_FEATURE_IMPLEMENTATION),
            Map.entry(ScenarioCategory.CORRECTNESS_BUG, RubricDimension.CORRECTNESS_AND_FEATURE_IMPLEMENTATION),
            Map.entry(ScenarioCategory.API_RELIABILITY, RubricDimension.API_DESIGN_AND_INTEGRATION),
            Map.entry(ScenarioCategory.ERROR_HANDLING, RubricDimension.ERROR_HANDLING_AND_RESILIENCE),
            Map.entry(ScenarioCategory.DATABASE_CORRECTNESS, RubricDimension.DATA_AND_PERSISTENCE),
            Map.entry(ScenarioCategory.DATABASE_PERFORMANCE, RubricDimension.DATA_AND_PERSISTENCE),
            Map.entry(ScenarioCategory.CONCURRENCY_CONSISTENCY, RubricDimension.CONCURRENCY_AND_CONSISTENCY),
            Map.entry(ScenarioCategory.CACHING, RubricDimension.PERFORMANCE_AND_EFFICIENCY),
            Map.entry(ScenarioCategory.SCALABILITY, RubricDimension.SYSTEM_DESIGN_AND_SCALABILITY),
            Map.entry(ScenarioCategory.ARCHITECTURE_REFACTORING, RubricDimension.ARCHITECTURE_AND_MODULARITY),
            Map.entry(ScenarioCategory.SECURITY, RubricDimension.SECURITY),
            Map.entry(ScenarioCategory.TESTING_GAP, RubricDimension.TESTING_AND_QUALITY_ASSURANCE),
            Map.entry(ScenarioCategory.PRODUCTION_READINESS, RubricDimension.PRODUCTION_READINESS_AND_OPERATIONS),
            Map.entry(ScenarioCategory.DEPENDENCY_FAILURE, RubricDimension.DEPENDENCIES_AND_EXTERNAL_SERVICES),
            Map.entry(ScenarioCategory.OBSERVABILITY_OPERATIONS, RubricDimension.PRODUCTION_READINESS_AND_OPERATIONS),
            Map.entry(ScenarioCategory.FRONTEND_CLIENT, RubricDimension.FRONTEND_CLIENT_ENGINEERING),
            Map.entry(ScenarioCategory.INFRASTRUCTURE_DEPLOYMENT, RubricDimension.PRODUCTION_READINESS_AND_OPERATIONS),
            Map.entry(ScenarioCategory.CLOUD_ARCHITECTURE, RubricDimension.SYSTEM_DESIGN_AND_SCALABILITY),
            Map.entry(ScenarioCategory.NETWORK_BEHAVIOR, RubricDimension.ERROR_HANDLING_AND_RESILIENCE)));

    /**
     * AI/ML is a practice category, not an engineering area: an AI/ML scenario counts under the dimension its own
     * problem is about. The first matching group wins, in this order; with no match it is a correctness problem
     * (model output, parsing, inference logic).
     */
    private static final Map<RubricDimension, List<String>> AI_ML_KEYWORDS = new LinkedHashMap<>();

    static {
        AI_ML_KEYWORDS.put(RubricDimension.SECURITY, List.of("prompt injection", "injection", "secret", "api key", "leak", "pii",
                "authoriz", "authentic"));
        AI_ML_KEYWORDS.put(RubricDimension.ERROR_HANDLING_AND_RESILIENCE, List.of("retry", "retries", "timeout", "fallback", "outage",
                "rate limit", "circuit breaker", "failover", "degrad"));
        AI_ML_KEYWORDS.put(RubricDimension.CONCURRENCY_AND_CONSISTENCY, List.of("concurren", "race condition", "parallel"));
        AI_ML_KEYWORDS.put(RubricDimension.PERFORMANCE_AND_EFFICIENCY, List.of("latency", "cost", "token budget", "cache", "caching",
                "batching", "throughput"));
        AI_ML_KEYWORDS.put(RubricDimension.TESTING_AND_QUALITY_ASSURANCE, List.of("evaluation", "benchmark", "golden", "regression test",
                "test"));
        AI_ML_KEYWORDS.put(RubricDimension.PRODUCTION_READINESS_AND_OPERATIONS, List.of("monitor", "observab", "logging", "drift",
                "deploy", "model version"));
        AI_ML_KEYWORDS.put(RubricDimension.DEPENDENCIES_AND_EXTERNAL_SERVICES, List.of("provider", "vendor", "sdk", "third-party"));
        AI_ML_KEYWORDS.put(RubricDimension.DATA_AND_PERSISTENCE, List.of("dataset", "feature store", "vector store", "embedding store",
                "data pipeline"));
    }

    /**
     * The area a scenario answer counts under.
     *
     * @param matched for AI/ML scenarios, the phrase that decided the area (null for the default and for other categories)
     */
    public record Mapping(RubricDimension area, String matched) {
    }

    /** @param concepts the scenario's expected concepts (server-side only; never returned to the browser) */
    public static Mapping areaOf(ScenarioCategory category, String title, List<String> concepts) {
        if (category != ScenarioCategory.AI_ML_ENGINEERING) {
            return new Mapping(AREAS.get(category), null);
        }
        String text = ((title == null ? "" : title) + " " + String.join(" ", concepts == null ? List.of() : concepts))
                .toLowerCase(Locale.ROOT);
        for (Map.Entry<RubricDimension, List<String>> group : AI_ML_KEYWORDS.entrySet()) {
            for (String keyword : group.getValue()) {
                // At the start of a word, so "latest" isn't "test" and "contest" isn't "test" either.
                if (Pattern.compile("\\b" + Pattern.quote(keyword)).matcher(text).find()) {
                    return new Mapping(group.getKey(), keyword);
                }
            }
        }
        return new Mapping(RubricDimension.CORRECTNESS_AND_FEATURE_IMPLEMENTATION, null);
    }
}
