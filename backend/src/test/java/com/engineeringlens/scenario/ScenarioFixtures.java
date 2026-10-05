package com.engineeringlens.scenario;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.scenario.execution.ExecutionRequest;
import com.engineeringlens.scenario.execution.ExecutionResult;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a well-behaved model answers to the generation prompts, and how a sandbox would judge the resulting
 * workspace: the starter misses "ignores a repeated request"; code marked "# FIXED" passes everything.
 */
public final class ScenarioFixtures {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final List<String> CHECKS = List.of("places an order", "ignores a repeated request", "keeps different orders apart");
    public static final String STARTER = "def place(order, store):\n    store.append(order)\n    return order\n";
    public static final String FIXED = "def place(order, store):\n    if order in store:  # FIXED\n        return order\n"
            + "    store.append(order)\n    return order\n";

    private static final Pattern COUNT = Pattern.compile("Propose exactly (\\d+) scenario outlines");
    private static final Pattern ROLE = Pattern.compile("ROLE = \"([A-Z_]+)\"");
    private static final Pattern ALLOWED_CATEGORIES = Pattern.compile("CATEGORY = (.+)");
    private static final Pattern DIFFICULTY = Pattern.compile("DIFFICULTY = \"([A-Z_]+)\"");

    private ScenarioFixtures() {
    }

    public static boolean isPlan(AiPrompt p) {
        return p.system().contains("Propose exactly");
    }

    public static boolean isBuild(AiPrompt p) {
        return p.system().contains("Turn the outline below into a complete scenario");
    }

    public static boolean isApproachBuild(AiPrompt p) {
        return isBuild(p) && p.system().contains("APPROACH-ONLY SCENARIO");
    }

    /** Categories and files the fake plan rotates through, like a sensible model spreading a large lab. */
    private static final List<String> CATEGORIES = List.of("CONCURRENCY_CONSISTENCY", "ERROR_HANDLING", "API_RELIABILITY", "TESTING_GAP",
            "SECURITY", "DATABASE_CORRECTNESS", "PRODUCTION_READINESS", "CORRECTNESS_BUG");
    private static final List<String> GROUNDING = List.of("app/services/orders.py", "app/main.py", "tests/test_orders.py",
            "requirements.txt", "README.md");

    /** A plan with exactly the requested number of outlines, spread over categories and the repository's files. */
    public static String plan(AiPrompt prompt) {
        Matcher count = COUNT.matcher(prompt.system());
        Matcher role = ROLE.matcher(prompt.system());
        int n = count.find() ? Integer.parseInt(count.group(1)) : 5;
        String r = role.find() ? role.group(1) : "BACKEND_ENGINEER";
        // Like a model that follows the contract: only the categories and difficulties the prompt allows.
        Matcher allowed = ALLOWED_CATEGORIES.matcher(prompt.system());
        String allowedList = allowed.find() ? allowed.group(1) : "";
        List<String> categories = CATEGORIES.stream().filter(c -> allowedList.isEmpty() || allowedList.contains("\"" + c + "\"")).toList();
        Matcher difficulty = DIFFICULTY.matcher(prompt.system());
        String level = difficulty.find() ? difficulty.group(1) : "INTERMEDIATE";
        boolean code = !prompt.system().contains("No code can be executed");
        ObjectNode plan = JSON.createObjectNode();
        plan.put("scenarioPlanSchemaVersion", 1);
        plan.put("repositorySummary", "A small FastAPI order service.");
        ArrayNode outlines = plan.putArray("outlines");
        for (int i = 1; i <= n; i++) {
            ObjectNode o = outlines.addObject();
            o.put("key", "S" + i);
            o.put("title", "Prevent duplicate orders, variant " + i);
            o.put("role", r);
            o.put("category", categories.get((i - 1) % categories.size()));
            o.put("difficulty", level);
            o.put("mode", code ? "CODE" : "APPROACH_ONLY");
            if (code) {
                o.put("language", "PYTHON");
            } else {
                o.putNull("language");
            }
            o.put("problem", "A retried checkout request stores the same order twice.");
            ObjectNode g = o.putArray("groundedIn").addObject();
            g.put("file", GROUNDING.get((i - 1) % GROUNDING.size()));
            g.put("lineStart", 1);
            g.put("lineEnd", 1);
            g.put("why", "place() appends without checking for an existing order");
            o.putArray("expectedConcepts").add("idempotency");
            o.put("whyItFitsTheLevel", "A contained fix with a real trade-off.");
        }
        return plan.toString();
    }

    /** A complete scenario; executable unless the prompt asks for approach-only. */
    public static String scenario(AiPrompt prompt, String starter) {
        ObjectNode s = JSON.createObjectNode();
        s.put("scenarioSchemaVersion", 1);
        s.put("title", "Prevent duplicate orders under retries");
        s.put("summary", "Retried checkout requests create duplicate orders.");
        s.put("incident", "Customers who double-click Pay are charged twice: two identical orders appear.");
        s.put("context", "Orders are created by place() in app/services/orders.py and stored in a list.");
        s.put("task", "Make place() safe to call twice with the same order without breaking normal orders.");
        s.putArray("expectedBehaviour").add("A repeated request returns the existing order");
        s.putArray("constraints").add("Keep the function signature");
        ObjectNode e = s.putArray("evidence").addObject();
        e.put("file", "app/services/orders.py");
        e.put("lineStart", 2);
        e.put("lineEnd", 2);
        e.put("explanation", "Appends unconditionally");
        s.putArray("expectedConcepts").add("idempotency").add("uniqueness constraints");
        ArrayNode rubric = s.putArray("rubric");
        rubric.addObject().put("criterion", "Correctness").put("whatGoodLooksLike", "No duplicates, normal orders unaffected");
        rubric.addObject().put("criterion", "Trade-offs").put("whatGoodLooksLike", "Explains where uniqueness should be enforced");
        s.put("referenceReasoning", "Make creation idempotent and enforce uniqueness where the data lives.");
        if (isApproachBuild(prompt)) {
            s.putNull("workspace");
            s.putNull("checks");
            s.putNull("referenceSolution");
            return s.toString();
        }
        ObjectNode w = s.putObject("workspace");
        w.put("language", "PYTHON");
        ObjectNode f = w.putArray("files").addObject();
        f.put("path", "orders.py");
        f.put("content", starter);
        f.put("editable", true);
        ObjectNode c = s.putObject("checks");
        c.put("source", "from engiens import check\nimport orders\n# HIDDEN_CHECK_SOURCE\n");
        ArrayNode names = c.putArray("checkNames");
        CHECKS.forEach(names::add);
        ObjectNode ref = s.putObject("referenceSolution");
        ref.putArray("files").addObject().put("path", "orders.py").put("content", FIXED);
        ref.put("explanation", "Return the existing order when it was already placed.");
        return s.toString();
    }

    public static boolean isAssess(AiPrompt p) {
        return p.system().contains("evaluating how a developer solved an engineering scenario");
    }

    public static boolean isSummary(AiPrompt p) {
        return p.system().contains("overall assessment of an engineering scenario lab");
    }

    public static boolean isLabTeaching(AiPrompt p) {
        return p.system().contains("after their scenario lab was assessed");
    }

    public static String evaluation() {
        return """
                {"scenarioEvaluationSchemaVersion":1,"verdict":"SOLID","confidence":"MEDIUM",
                 "assessment":"The fix prevents duplicates for sequential retries.",
                 "whatWasCorrect":["Checks for an existing order before storing"],"whatWasMissed":["Concurrent requests can still race"],
                 "rootCause":"Order creation is not idempotent.","engineeringJudgment":"Sound for one process.",
                 "tradeoffs":["A list scan is O(n)"],"scaleImpact":"Needs a unique constraint at the database under load.",
                 "regressionRisk":"Low: normal orders are unaffected.","testingAssessment":"Add a concurrent retry test.",
                 "recommendedFix":"Enforce uniqueness where the data lives.","referenceApproach":"Idempotency key plus unique index."}""";
    }

    public static String summary() {
        return """
                {"labSummarySchemaVersion":1,
                 "overallAssessment":{"summary":"Consistent, correct fixes with gaps under concurrency.",
                                      "engineeringLevel":"Meets SDE2 expectations on correctness; developing on concurrency.","confidence":"MEDIUM"},
                 "strengths":["Correct fixes"],"growthAreas":["Concurrency"],"limitations":["Five scenarios is a small sample"]}""";
    }

    /** Learning points for every scenario id the prompt mentions. */
    public static String labTeaching(AiPrompt prompt) {
        Matcher ids = Pattern.compile("\"scenarioId\":\"([0-9a-f-]{36})\"").matcher(prompt.user());
        ObjectNode t = JSON.createObjectNode();
        ArrayNode learning = t.putArray("scenarioLearning");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (ids.find()) {
            if (seen.add(ids.group(1))) {
                ObjectNode l = learning.addObject();
                l.put("scenarioId", ids.group(1));
                l.putArray("learningPoints").add("Learn how unique constraints make retries safe");
            }
        }
        t.putArray("learningRecommendations").addObject().put("topic", "Idempotency").put("why", "Retries are everywhere")
                .put("connectionToProject", "Checkout in app/services/orders.py");
        return t.toString();
    }

    /** The sandbox's verdict: "ignores a repeated request" fails until the code is fixed. */
    public static ExecutionResult judge(ExecutionRequest request) {
        boolean fixed = request.files().values().stream().anyMatch(v -> v.contains("# FIXED"));
        List<String> lines = new java.util.ArrayList<>();
        for (String name : CHECKS) {
            boolean passed = fixed || !name.equals("ignores a repeated request");
            lines.add("{\"kind\":\"check\",\"name\":\"" + name + "\",\"passed\":" + passed + ",\"message\":"
                    + (passed ? "null" : "\"a repeated request created a second order\"") + ",\"ms\":2}");
        }
        lines.add("{\"kind\":\"summary\",\"outcome\":\"ran\"}");
        return new ExecutionResult(0, false, "placing", "", false, lines, 700);
    }
}
