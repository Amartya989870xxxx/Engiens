package com.engineeringlens.analysis.review;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.InvalidAiOutputException;
import com.engineeringlens.analysis.review.model.PersonalizedTeaching;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.RubricDimension;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Treats model output as untrusted input. Parses it, makes every evidence reference point at something
 * real, applies caps, binds it to the typed schema and validates it. Anything that fails throws
 * {@link InvalidAiOutputException} with a short reason (used for the one repair attempt); nothing invalid
 * is ever returned, stored or shown.
 */
@Component
public class ReviewValidator {

    private static final int MAX_ACTIONS = 5;
    private static final int MAX_HIGHLIGHTS = 5;

    /** Strict about types and enums; tolerant only of extra fields the model adds. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .build();

    private final Validator validator;

    public ReviewValidator(Validator validator) {
        this.validator = validator;
    }

    /** What evidence may legally point at, built from the context the model saw. */
    public record EvidenceIndex(Set<String> repositoryPaths, Map<String, Integer> includedLines, Set<String> signalIds) {

        public static EvidenceIndex of(LoadedContext loaded) {
            return new EvidenceIndex(loaded.repositoryPaths(), loaded.includedLines(),
                    loaded.context().analysis().signals().stream().map(s -> s.ruleId()).collect(Collectors.toUnmodifiableSet()));
        }
    }

    /**
     * @param evidenceDropped  references to files/signals that don't exist (removed)
     * @param linesRemoved     line ranges that didn't fit the content shown (file kept, lines removed)
     */
    public record Validated(ReviewDocument document, int evidenceDropped, int linesRemoved) {
    }

    public Validated validate(String raw, EvidenceIndex index) {
        JsonNode root;
        try {
            root = MAPPER.readTree(stripFences(raw));
        } catch (RuntimeException e) {
            throw new InvalidAiOutputException("The answer is not valid JSON.");
        }
        if (!(root instanceof ObjectNode doc)) {
            throw new InvalidAiOutputException("The answer must be a single JSON object.");
        }
        int[] counts = { 0, 0 };
        sanitiseEvidence(doc, index, counts);
        normalise(doc);
        doc.remove("reviewMetadata"); // always set by Engiens
        doc.remove("personalization");

        ReviewDocument review;
        try {
            review = MAPPER.treeToValue(doc, ReviewDocument.class);
        } catch (RuntimeException e) {
            throw new InvalidAiOutputException("Schema mismatch: " + firstLine(e.getMessage()));
        }
        List<String> problems = new ArrayList<>();
        for (ConstraintViolation<ReviewDocument> v : validator.validate(review)) {
            problems.add(v.getPropertyPath() + " " + v.getMessage());
        }
        if (review.reviewSchemaVersion() != null && review.reviewSchemaVersion() != ReviewDocument.SCHEMA_VERSION) {
            problems.add("reviewSchemaVersion must be " + ReviewDocument.SCHEMA_VERSION);
        }
        if (review.dimensions() != null) {
            EnumSet<RubricDimension> seen = EnumSet.noneOf(RubricDimension.class);
            for (ReviewDocument.DimensionReview d : review.dimensions()) {
                if (d != null && d.id() != null && !seen.add(d.id())) {
                    problems.add("dimension " + d.id() + " appears twice");
                }
            }
            EnumSet<RubricDimension> missing = EnumSet.complementOf(seen);
            if (!missing.isEmpty() && review.dimensions().size() == RubricDimension.values().length) {
                problems.add("missing dimensions " + missing);
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidAiOutputException(problems.stream().sorted().limit(6).collect(Collectors.joining("; ")));
        }
        return new Validated(review, counts[0], counts[1]);
    }

    /**
     * Validates the personalisation step's answer. Extra fields (say, an "assessment" the model added) are
     * ignored by design: the type only has room for advice and the learning plan.
     */
    public PersonalizedTeaching validateTeaching(String raw) {
        PersonalizedTeaching teaching;
        try {
            teaching = MAPPER.readValue(stripFences(raw), PersonalizedTeaching.class);
        } catch (RuntimeException e) {
            throw new InvalidAiOutputException("Not valid JSON for the contract: " + firstLine(e.getMessage()));
        }
        if (teaching == null) {
            throw new InvalidAiOutputException("The answer must be a single JSON object.");
        }
        List<String> problems = new ArrayList<>();
        for (ConstraintViolation<PersonalizedTeaching> v : validator.validate(teaching)) {
            problems.add(v.getPropertyPath() + " " + v.getMessage());
        }
        if (teaching.dimensions() != null) {
            EnumSet<RubricDimension> seen = EnumSet.noneOf(RubricDimension.class);
            teaching.dimensions().stream().filter(d -> d != null && d.id() != null && !seen.add(d.id()))
                    .forEach(d -> problems.add("dimension " + d.id() + " appears twice"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidAiOutputException(problems.stream().sorted().limit(6).collect(Collectors.joining("; ")));
        }
        return teaching;
    }

    /** Walks the whole document and fixes every "evidence" array against what really exists. */
    private static void sanitiseEvidence(JsonNode node, EvidenceIndex index, int[] counts) {
        if (node instanceof ObjectNode obj) {
            for (String field : List.copyOf(obj.propertyNames())) {
                JsonNode child = obj.get(field);
                if (field.equals("evidence") && child instanceof ArrayNode evidence) {
                    cleanEvidence(evidence, index, counts);
                } else {
                    sanitiseEvidence(child, index, counts);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(child -> sanitiseEvidence(child, index, counts));
        }
    }

    private static void cleanEvidence(ArrayNode evidence, EvidenceIndex index, int[] counts) {
        Iterator<JsonNode> it = evidence.iterator();
        while (it.hasNext()) {
            JsonNode e = it.next();
            if (!(e instanceof ObjectNode item)) {
                it.remove();
                counts[0]++;
                continue;
            }
            String signalId = text(item, "signalId");
            String file = text(item, "file");
            if (file != null) {
                file = file.replaceFirst("^(\\./|/)", "");
                item.put("file", file);
            }
            if (file == null && signalId != null) {
                if (!index.signalIds().contains(signalId)) {
                    it.remove(); // an invented signal id
                    counts[0]++;
                }
                continue;
            }
            if (file != null && signalId == null) {
                if (!index.repositoryPaths().contains(file)) {
                    it.remove(); // an invented path
                    counts[0]++;
                    continue;
                }
                Integer shown = index.includedLines().get(file);
                JsonNode start = item.get("lineStart");
                if (start != null && !start.isNull()) {
                    int s = start.asInt();
                    JsonNode endNode = item.get("lineEnd");
                    int end = endNode == null || endNode.isNull() ? s : endNode.asInt();
                    if (shown == null || s < 1 || s > shown || end < s) {
                        // Lines the model couldn't have seen: keep the file, drop the invented location.
                        item.putNull("lineStart");
                        item.putNull("lineEnd");
                        counts[1]++;
                    } else if (end > shown) {
                        item.put("lineEnd", shown);
                        counts[1]++;
                    }
                }
            }
            // Both or neither present: left for schema validation to reject as malformed.
        }
    }

    /** Caps and obvious consistency fixes that don't change any judgement. */
    private static void normalise(ObjectNode doc) {
        if (doc.get("dimensions") instanceof ArrayNode dims) {
            for (JsonNode d : dims) {
                if (d instanceof ObjectNode dim && "NOT_APPLICABLE".equals(text(dim, "applicability"))) {
                    dim.put("assessment", "NOT_ASSESSABLE");
                }
            }
        }
        if (doc.get("priorityActions") instanceof ArrayNode actions && actions.size() > MAX_ACTIONS) {
            List<JsonNode> sorted = new ArrayList<>();
            actions.forEach(sorted::add);
            sorted.sort((a, b) -> Integer.compare(a.path("priority").asInt(Integer.MAX_VALUE), b.path("priority").asInt(Integer.MAX_VALUE)));
            actions.removeAll();
            sorted.stream().limit(MAX_ACTIONS).forEach(actions::add);
        }
        if (doc.get("positiveHighlights") instanceof ArrayNode highlights) {
            while (highlights.size() > MAX_HIGHLIGHTS) {
                highlights.remove(highlights.size() - 1);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString().strip();
    }

    /** Models sometimes wrap JSON in ``` fences despite instructions; unwrap them rather than fail. */
    static String stripFences(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.strip();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable structure";
        }
        String line = message.lines().findFirst().orElse(message);
        return line.length() <= 200 ? line : line.substring(0, 200);
    }
}
