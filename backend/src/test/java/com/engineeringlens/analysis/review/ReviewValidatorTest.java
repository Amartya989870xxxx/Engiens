package com.engineeringlens.analysis.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.ai.InvalidAiOutputException;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.RubricDimension;

import jakarta.validation.Validation;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ReviewValidatorTest {

    private ReviewValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReviewValidator(Validation.buildDefaultValidatorFactory().getValidator());
    }

    private ReviewValidator.Validated validate(ObjectNode doc) {
        return validator.validate(ReviewFixtures.JSON.writeValueAsString(doc), ReviewFixtures.INDEX);
    }

    private void assertRejected(ObjectNode doc, String reasonFragment) {
        assertThatThrownBy(() -> validate(doc)).isInstanceOf(InvalidAiOutputException.class).hasMessageContaining(reasonFragment);
    }

    private static ObjectNode firstDimension(ObjectNode doc) {
        return (ObjectNode) doc.get("dimensions").get(0);
    }

    @Test
    void acceptsAValidReviewAndKeepsItsEvidence() {
        ReviewValidator.Validated v = validate(ReviewFixtures.valid());
        ReviewDocument d = v.document();
        assertThat(d.dimensions()).hasSize(16);
        assertThat(d.dimensions()).extracting(ReviewDocument.DimensionReview::id).containsExactly(RubricDimension.values());
        ReviewDocument.Evidence e = d.dimensions().get(0).strengths().get(0).evidence().get(0);
        assertThat(e.file()).isEqualTo("app/services/orders.py");
        assertThat(e.lineStart()).isEqualTo(3);
        assertThat(e.lineEnd()).isEqualTo(18);
        assertThat(v.evidenceDropped()).isZero();
        assertThat(v.linesRemoved()).isZero();
    }

    @Test
    void unwrapsMarkdownFences() {
        String fenced = "```json\n" + ReviewFixtures.validJson() + "\n```";
        assertThat(validator.validate(fenced, ReviewFixtures.INDEX).document().dimensions()).hasSize(16);
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> validator.validate("Here is your review: great job!", ReviewFixtures.INDEX))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsMissingRequiredFields() {
        ObjectNode doc = ReviewFixtures.valid();
        ((ObjectNode) doc.get("executiveSummary")).remove("biggestOpportunity");
        assertRejected(doc, "executiveSummary.biggestOpportunity");
    }

    @Test
    void rejectsValuesOutsideTheEnums() {
        ObjectNode doc = ReviewFixtures.valid();
        firstDimension(doc).put("assessment", "EXCELLENT");
        assertRejected(doc, "Schema mismatch");
    }

    @Test
    void rejectsMalformedEvidence() {
        ObjectNode doc = ReviewFixtures.valid();
        ObjectNode both = ReviewFixtures.evidence("app/main.py", null, null).put("signalId", "TESTING.TEST_FILES");
        ((ArrayNode) firstDimension(doc).get("strengths").get(0).get("evidence")).add(both);
        assertRejected(doc, "evidence must reference exactly one of file or signalId");
    }

    @Test
    void rejectsMissingOrDuplicatedDimensions() {
        ObjectNode fifteen = ReviewFixtures.valid();
        ((ArrayNode) fifteen.get("dimensions")).remove(15);
        assertRejected(fifteen, "dimensions size must be between 16 and 16");

        ObjectNode duplicated = ReviewFixtures.valid();
        ((ObjectNode) duplicated.get("dimensions").get(1)).put("id", "CORRECTNESS_AND_FEATURE_IMPLEMENTATION");
        assertRejected(duplicated, "appears twice");
    }

    @Test
    void inventedFilesAndSignalsAreDroppedAndImpossibleLinesRemoved() {
        ObjectNode doc = ReviewFixtures.valid();
        ArrayNode evidence = (ArrayNode) firstDimension(doc).get("strengths").get(0).get("evidence");
        evidence.add(ReviewFixtures.evidence("src/does/not/exist.py", 1, 2)); // invented path → dropped
        evidence.add(ReviewFixtures.signal("TESTING.MADE_UP")); // invented signal → dropped
        evidence.add(ReviewFixtures.evidence("app/main.py", 90, 95)); // file has 12 shown lines → lines removed
        evidence.add(ReviewFixtures.evidence("README.md", 1, 3)); // README content wasn't shown → lines removed
        evidence.add(ReviewFixtures.evidence("app/services/orders.py", 30, 400)); // end past 40 → clamped

        ReviewValidator.Validated v = validate(doc);

        var kept = v.document().dimensions().get(0).strengths().get(0).evidence();
        assertThat(kept).extracting(ReviewDocument.Evidence::file)
                .containsExactly("app/services/orders.py", "app/main.py", "README.md", "app/services/orders.py");
        assertThat(kept.get(1).lineStart()).isNull();
        assertThat(kept.get(2).lineStart()).isNull();
        assertThat(kept.get(3).lineEnd()).isEqualTo(40);
        assertThat(v.evidenceDropped()).isEqualTo(2);
        assertThat(v.linesRemoved()).isEqualTo(3);
    }

    @Test
    void notApplicableDimensionsAreNotAssessable() {
        ObjectNode doc = ReviewFixtures.valid();
        ((ObjectNode) doc.get("dimensions").get(15)).put("applicability", "NOT_APPLICABLE").put("assessment", "STRONG");
        assertThat(validate(doc).document().dimensions().get(15).assessment()).isEqualTo(Assessment.NOT_ASSESSABLE);
    }

    @Test
    void keepsAtMostFiveActionsInPriorityOrder() {
        ObjectNode doc = ReviewFixtures.valid();
        ArrayNode actions = (ArrayNode) doc.get("priorityActions");
        ObjectNode template = (ObjectNode) actions.get(0);
        actions.removeAll();
        for (int p = 7; p >= 1; p--) {
            actions.add(template.deepCopy().put("priority", p));
        }
        assertThat(validate(doc).document().priorityActions()).extracting(ReviewDocument.PriorityAction::priority)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void metadataFromTheModelIsIgnored() {
        ObjectNode doc = ReviewFixtures.valid();
        doc.putObject("reviewMetadata").put("provider", "made-up").put("model", "gpt-99");
        assertThat(validate(doc).document().reviewMetadata()).isNull();
    }

    @Test
    void personalisationCanOnlyChangeAdviceAndTheLearningPlan() {
        ReviewDocument neutral = validate(ReviewFixtures.valid()).document();
        ObjectNode teaching = ReviewFixtures.teaching();
        // A model that tries to regrade is ignored: the teaching type has no room for assessments.
        ((ObjectNode) teaching.get("dimensions").get(0)).put("assessment", "NEEDS_ATTENTION").put("severity", "HIGH");
        teaching.put("overallAssessment", "NEEDS_ATTENTION");

        ReviewDocument taught = neutral.withTeaching(validator.validateTeaching(ReviewFixtures.JSON.writeValueAsString(teaching)));

        assertThat(taught.dimensions()).extracting(ReviewDocument.DimensionReview::assessment)
                .containsExactlyElementsOf(neutral.dimensions().stream().map(ReviewDocument.DimensionReview::assessment).toList());
        assertThat(taught.overallAssessment()).isEqualTo(neutral.overallAssessment());
        assertThat(taught.dimensions()).extracting(ReviewDocument.DimensionReview::concerns)
                .containsExactlyElementsOf(neutral.dimensions().stream().map(ReviewDocument.DimensionReview::concerns).toList());
        ReviewDocument.DimensionReview testing = taught.dimensions().stream()
                .filter(d -> d.id() == RubricDimension.TESTING_AND_QUALITY_ASSURANCE).findFirst().orElseThrow();
        assertThat(testing.personalizedAdvice()).containsExactly("Start with one test for placing an order: it's the path users care about most.");
        // Dimensions the step didn't mention get no advice rather than the general text.
        assertThat(taught.dimensions().get(0).personalizedAdvice()).isEmpty();
        assertThat(taught.personalizedLearningPlan().nextThingsToLearn()).extracting(ReviewDocument.LearningTopic::topic)
                .containsExactly("Writing your first integration test");
    }

    @Test
    void rejectsMalformedPersonalisation() {
        ObjectNode duplicate = ReviewFixtures.teaching();
        ((ArrayNode) duplicate.get("dimensions")).addObject().put("id", "SECURITY").putArray("personalizedAdvice");
        assertThatThrownBy(() -> validator.validateTeaching(ReviewFixtures.JSON.writeValueAsString(duplicate)))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("appears twice");

        ObjectNode unknown = ReviewFixtures.teaching();
        ((ObjectNode) unknown.get("dimensions").get(0)).put("id", "VIBES");
        assertThatThrownBy(() -> validator.validateTeaching(ReviewFixtures.JSON.writeValueAsString(unknown)))
                .isInstanceOf(InvalidAiOutputException.class);

        ObjectNode noPlan = ReviewFixtures.teaching();
        noPlan.remove("personalizedLearningPlan");
        assertThatThrownBy(() -> validator.validateTeaching(ReviewFixtures.JSON.writeValueAsString(noPlan)))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("personalizedLearningPlan");
    }
}
