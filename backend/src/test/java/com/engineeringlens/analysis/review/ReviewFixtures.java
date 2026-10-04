package com.engineeringlens.analysis.review;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.engineeringlens.analysis.review.model.RubricDimension;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A complete, valid review as a model might return it, for tests to mutate. */
public final class ReviewFixtures {

    public static final ObjectMapper JSON = new ObjectMapper();

    /** The context the fixture's evidence refers to. */
    public static final ReviewValidator.EvidenceIndex INDEX = new ReviewValidator.EvidenceIndex(
            Set.of("app/services/orders.py", "app/main.py", "README.md"),
            Map.of("app/services/orders.py", 40, "app/main.py", 12),
            Set.of("TESTING.TEST_FILES", "PRODUCTION_READINESS.CI"));

    private ReviewFixtures() {
    }

    static ObjectNode evidence(String file, Integer start, Integer end) {
        ObjectNode e = JSON.createObjectNode();
        e.put("file", file);
        if (start == null) {
            e.putNull("lineStart");
        } else {
            e.put("lineStart", start);
        }
        if (end == null) {
            e.putNull("lineEnd");
        } else {
            e.put("lineEnd", end);
        }
        return e;
    }

    static ObjectNode signal(String id) {
        return JSON.createObjectNode().put("signalId", id);
    }

    private static ArrayNode strings(String... values) {
        ArrayNode a = JSON.createArrayNode();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    private static ObjectNode area(String assessment) {
        ObjectNode a = JSON.createObjectNode().put("assessment", assessment);
        a.set("concerns", strings("Writes happen synchronously in the request."));
        return a;
    }

    public static ObjectNode valid() {
        ObjectNode r = JSON.createObjectNode().put("reviewSchemaVersion", 1);
        ObjectNode overall = r.putObject("overallAssessment").put("level", "SOLID").put("confidence", "MEDIUM")
                .put("summary", "A tidy service with clear layers.");
        overall.set("strongestAreas", strings("ARCHITECTURE_AND_MODULARITY"));
        overall.set("highestPriorityAreas", strings("TESTING_AND_QUALITY_ASSURANCE"));
        r.putObject("executiveSummary").put("whatThisProjectDoes", "An order API.").put("engineeringSummary", "Layered FastAPI app.")
                .put("strongestAspect", "Separation of concerns.").put("biggestOpportunity", "Tests for the order flow.")
                .put("overallScaleConcern", "Synchronous writes could become a bottleneck at substantially higher traffic.");
        ObjectNode pu = r.putObject("projectUnderstanding").put("projectType", "REST API").put("architectureSummary", "Routers → services → DB.");
        pu.set("detectedStack", strings("Python", "FastAPI"));
        pu.set("importantComponents", strings("app/services/orders.py"));

        ArrayNode dims = r.putArray("dimensions");
        for (RubricDimension d : RubricDimension.values()) {
            ObjectNode dim = dims.addObject().put("id", d.name()).put("name", d.displayName()).put("applicability", "APPLICABLE")
                    .put("assessment", "SOLID").put("confidence", "MEDIUM").put("summary", "Reasonable for the project size.");
            ObjectNode strength = dim.putArray("strengths").addObject().put("title", "Clear service layer")
                    .put("description", "Business rules live in services.");
            strength.putArray("evidence").add(evidence("app/services/orders.py", 3, 18));
            ObjectNode concern = dim.putArray("concerns").addObject().put("id", d.name().substring(0, 4) + "-001")
                    .put("title", "No retry around the payment call").put("severity", "MEDIUM").put("confidence", "MEDIUM")
                    .put("description", "The external call has no timeout.").put("whyItMatters", "A slow dependency blocks requests.")
                    .put("engineeringImpact", "Request threads can pile up.").put("recommendation", "Add a timeout and a bounded retry.")
                    .put("suggestedDirection", "Wrap the client.");
            concern.putObject("scaleImpact").put("currentScale", "Unlikely to matter.").put("tenX", "Could become visible.")
                    .putNull("hundredX").putNull("largeScale").put("confidence", "LOW");
            concern.putArray("evidence").add(signal("PRODUCTION_READINESS.CI"));
            concern.putObject("learningValue").put("currentLevel", "Timeouts").put("nextLevel", "Retries with backoff")
                    .put("advancedLevel", "Circuit breakers");
            dim.putArray("tradeoffs").addObject().put("decision", "Single service").put("benefit", "Simple to run")
                    .put("cost", "Harder to scale parts independently").put("assessment", "REASONABLE");
            dim.set("personalizedAdvice", strings("Try adding a timeout to one external call."));
        }

        ObjectNode finding = r.putArray("crossCuttingFindings").addObject().put("id", "F-001").put("title", "Untested order flow")
                .put("category", "TESTING_AND_QUALITY_ASSURANCE").put("severity", "HIGH").put("confidence", "MEDIUM")
                .put("description", "d").put("whyItMatters", "w").put("engineeringImpact", "e").put("recommendation", "r")
                .putNull("exampleApproach").putNull("scaleImpact");
        finding.putArray("evidence").add(signal("TESTING.TEST_FILES"));

        ObjectNode feature = r.putArray("featureEngineeringReview").addObject().put("feature", "Orders");
        feature.putObject("correctness").put("assessment", "SOLID").put("summary", "Happy path is complete.");
        feature.putObject("implementationQuality").put("assessment", "DEVELOPING").put("summary", "Validation is thin.");
        ObjectNode edges = feature.putObject("edgeCases");
        edges.set("handled", strings("Empty cart"));
        edges.set("missing", strings("Duplicate submission"));
        feature.set("failureModes", strings("Payment timeout"));
        feature.set("scaleConsiderations", strings("Order writes are synchronous"));
        feature.set("recommendations", strings("Add an idempotency key"));
        feature.putArray("evidence").add(evidence("app/services/orders.py", 20, 35));

        ObjectNode scale = r.putObject("scaleReadiness").put("summary", "Fine for now.");
        scale.set("trafficGrowth", area("DEVELOPING"));
        scale.set("dataGrowth", area("SOLID"));
        scale.set("concurrency", area("NEEDS_ATTENTION"));
        scale.set("failureRecovery", area("DEVELOPING"));
        scale.set("operationalComplexity", area("SOLID"));
        scale.putArray("mostLikelyBottlenecks").addObject().put("component", "Order service").put("reason", "Synchronous writes")
                .put("confidence", "LOW");

        ObjectNode action = r.putArray("priorityActions").addObject().put("priority", 1).put("title", "Test the order flow")
                .put("reason", "Most valuable path").put("expectedBenefit", "Safer changes").put("difficulty", "MEDIUM");
        action.set("relatedDimensions", strings("TESTING_AND_QUALITY_ASSURANCE"));

        ObjectNode plan = r.putObject("personalizedLearningPlan");
        plan.set("youAlreadyDoWell", strings("Layering"));
        plan.putArray("nextThingsToLearn").addObject().put("topic", "Integration tests").put("why", "Protect the order flow")
                .put("connectionToProject", "orders.py has no tests").put("suggestedOrder", 1);
        plan.set("advancedTopics", strings("Idempotency"));

        ObjectNode highlight = r.putArray("positiveHighlights").addObject().put("title", "Clean routers")
                .put("description", "Thin route handlers.").put("whyThisIsGood", "Easy to test.");
        highlight.putArray("evidence").add(evidence("app/main.py", null, null));
        r.set("reviewLimitations", strings("Runtime traffic was not measured.", "Only selected files were provided."));
        return r;
    }

    public static String validJson() {
        return JSON.writeValueAsString(valid());
    }

    /** A valid personalisation answer: advice for two dimensions and a learning plan. */
    public static ObjectNode teaching() {
        ObjectNode t = JSON.createObjectNode();
        ArrayNode dims = t.putArray("dimensions");
        dims.addObject().put("id", "TESTING_AND_QUALITY_ASSURANCE").set("personalizedAdvice",
                strings("Start with one test for placing an order: it's the path users care about most."));
        dims.addObject().put("id", "SECURITY").set("personalizedAdvice", strings("Read about why secrets belong in environment variables."));
        ObjectNode plan = t.putObject("personalizedLearningPlan");
        plan.set("youAlreadyDoWell", strings("You keep routes thin."));
        plan.putArray("nextThingsToLearn").addObject().put("topic", "Writing your first integration test")
                .put("why", "It proves the order flow works end to end.").put("connectionToProject", "app/services/orders.py")
                .put("suggestedOrder", 1);
        plan.set("advancedTopics", strings("Transaction isolation"));
        return t;
    }

    public static String teachingJson() {
        return JSON.writeValueAsString(teaching());
    }

    public static List<String> dimensionIds() {
        return java.util.Arrays.stream(RubricDimension.values()).map(Enum::name).toList();
    }
}
