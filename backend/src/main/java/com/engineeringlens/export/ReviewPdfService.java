package com.engineeringlens.export;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.engineeringlens.analysis.review.ReviewRunResponse;
import com.engineeringlens.analysis.review.ReviewRunStatus;
import com.engineeringlens.analysis.review.ReviewService;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.ReviewDocument.Concern;
import com.engineeringlens.analysis.review.model.ReviewDocument.DimensionReview;
import com.engineeringlens.analysis.review.model.ReviewDocument.ScaleArea;
import com.engineeringlens.analysis.review.model.ReviewDocument.ScaleImpact;
import com.engineeringlens.analysis.review.model.ReviewEnums.Applicability;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.common.pdf.ReportPdf;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.scenario.history.LabHistoryItem;
import com.engineeringlens.scenario.history.ScenarioHistoryService;

/**
 * The engineering review as a PDF, typeset from the persisted review document. It never calls the AI: the
 * PDF says exactly what the review page says. Ownership is checked by the same lookups the page uses.
 */
@Service
public class ReviewPdfService {

    private final ReviewService reviews;
    private final ScenarioHistoryService history;
    private final ImportedRepoRepository repositories;

    public ReviewPdfService(ReviewService reviews, ScenarioHistoryService history, ImportedRepoRepository repositories) {
        this.reviews = reviews;
        this.history = history;
        this.repositories = repositories;
    }

    public record Pdf(String fileName, byte[] bytes) {
    }

    public Pdf render(UUID userId, UUID reviewId) {
        ReviewRunResponse run = reviews.get(userId, reviewId); // 404 for someone else's review
        if (run.status() != ReviewRunStatus.COMPLETED || run.review() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_COMPLETED", "Only a finished review can be exported.");
        }
        ReviewDocument r = run.review();
        String url = repositories.findById(run.repositoryId()).map(repo -> repo.getGithubUrl()).orElse(null);
        List<LabHistoryItem> labs = history.history(userId, run.repositoryId());
        try {
            byte[] bytes = ReportPdf.build("Engineering Review: " + run.repositoryName(), "Engiens  ·  Engineering Review  ·  " + run.repositoryName(),
                    pdf -> write(pdf, run, r, url, labs));
            return new Pdf("engiens-review-" + Words.slug(run.repositoryName()) + ".pdf", bytes);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF_GENERATION_FAILED", "The PDF couldn't be created. Please try again.");
        }
    }

    private static void write(ReportPdf pdf, ReviewRunResponse run, ReviewDocument r, String url, List<LabHistoryItem> labs) {
        List<String[]> meta = new ArrayList<>();
        meta.add(new String[] { "Repository", url == null ? run.repositoryName() : url });
        meta.add(new String[] { "Commit", run.commitSha() });
        meta.add(new String[] { "Review date", Words.date(run.completedAt()) });
        meta.add(new String[] { "Reviewer model", run.model() });
        if (r.personalization() != null) {
            meta.add(new String[] { "Advice written for", r.personalization().basis() });
        }
        pdf.cover("Engineering Review", "Engineering Review", run.repositoryName(), meta);

        var overall = r.overallAssessment();
        pdf.section("Overall assessment");
        pdf.label(Words.of(overall.level()) + "  ·  " + Words.of(overall.confidence()) + " confidence");
        pdf.paragraph(overall.summary());
        pdf.list("Strongest areas", Words.areas(overall.strongestAreas()));
        pdf.list("Highest-priority areas", Words.areas(overall.highestPriorityAreas()));

        var exec = r.executiveSummary();
        pdf.section("Executive summary");
        pdf.labeled("What this project does.", exec.whatThisProjectDoes());
        pdf.labeled("Engineering summary.", exec.engineeringSummary());
        pdf.labeled("Strongest aspect.", exec.strongestAspect());
        pdf.labeled("Biggest opportunity.", exec.biggestOpportunity());
        pdf.labeled("Scale concern.", exec.overallScaleConcern());

        var pu = r.projectUnderstanding();
        pdf.section("Project understanding");
        pdf.labeled("Project type.", pu.projectType());
        pdf.labeled("Architecture.", pu.architectureSummary());
        pdf.labeled("Detected stack.", String.join(", ", pu.detectedStack()));
        pdf.list("Important components", pu.importantComponents());

        pdf.section("Engineering dimensions");
        int n = 1;
        for (DimensionReview d : r.dimensions()) {
            dimension(pdf, n++, d);
        }

        if (!r.crossCuttingFindings().isEmpty()) {
            pdf.section("Cross-cutting findings");
            for (var f : r.crossCuttingFindings()) {
                pdf.subheading(f.title());
                pdf.label(Words.of(f.severity()) + " severity  ·  " + Words.of(f.confidence()) + " confidence  ·  " + f.category().displayName());
                pdf.paragraph(f.description());
                pdf.labeled("Why it matters.", f.whyItMatters());
                pdf.labeled("Engineering impact.", f.engineeringImpact());
                scale(pdf, f.scaleImpact());
                pdf.labeled("Recommendation.", f.recommendation());
                if (f.exampleApproach() != null && !f.exampleApproach().isBlank()) {
                    pdf.code("Example approach", f.exampleApproach());
                }
                pdf.labeled("Evidence.", Words.evidence(f.evidence()));
            }
        }

        if (!r.featureEngineeringReview().isEmpty()) {
            pdf.section("Feature engineering review");
            for (var f : r.featureEngineeringReview()) {
                pdf.subheading(f.feature());
                pdf.labeled("Correctness (" + Words.of(f.correctness().assessment()) + ").", f.correctness().summary());
                pdf.labeled("Implementation (" + Words.of(f.implementationQuality().assessment()) + ").", f.implementationQuality().summary());
                pdf.list("Edge cases handled", f.edgeCases().handled());
                pdf.list("Edge cases missing", f.edgeCases().missing());
                pdf.list("Failure modes", f.failureModes());
                pdf.list("Scale considerations", f.scaleConsiderations());
                pdf.list("Recommendations", f.recommendations());
            }
        }

        var scale = r.scaleReadiness();
        pdf.section("Scale readiness");
        pdf.paragraph(scale.summary());
        pdf.keyValues(List.of(area("Traffic growth", scale.trafficGrowth()), area("Data growth", scale.dataGrowth()),
                area("Concurrency", scale.concurrency()), area("Failure recovery", scale.failureRecovery()),
                area("Operational complexity", scale.operationalComplexity())));
        if (!scale.mostLikelyBottlenecks().isEmpty()) {
            pdf.subheading("Most likely bottlenecks");
            pdf.bullets(scale.mostLikelyBottlenecks().stream()
                    .map(b -> b.component() + ": " + b.reason() + " (" + Words.of(b.confidence()).toLowerCase() + " confidence)").toList());
        }

        if (!r.priorityActions().isEmpty()) {
            pdf.section("Priority actions");
            for (var a : r.priorityActions()) {
                pdf.subheading(a.priority() + ".  " + a.title());
                pdf.label(Words.of(a.difficulty()) + " difficulty");
                pdf.labeled("Why.", a.reason());
                pdf.labeled("Expected benefit.", a.expectedBenefit());
            }
        }

        var plan = r.personalizedLearningPlan();
        pdf.section("Personalised learning plan");
        pdf.list("You already do well", plan.youAlreadyDoWell());
        for (var t : plan.nextThingsToLearn()) {
            pdf.subheading(t.suggestedOrder() + ".  " + t.topic());
            pdf.labeled("Why.", t.why());
            pdf.labeled("In this project.", t.connectionToProject());
        }
        pdf.list("Advanced topics", plan.advancedTopics());

        if (!r.positiveHighlights().isEmpty()) {
            pdf.section("Positive highlights");
            for (var h : r.positiveHighlights()) {
                pdf.subheading(h.title());
                pdf.paragraph(h.description());
                pdf.labeled("Why this is good.", h.whyThisIsGood());
                pdf.labeled("Evidence.", Words.evidence(h.evidence()));
            }
        }

        pdf.section("Review limitations");
        pdf.bullets(r.reviewLimitations());
        pdf.paragraph("Engiens evaluates code against an explicit rubric of software engineering practices. It is not a complete "
                + "security audit and not a measure of anyone's professional worth.");

        pdf.section("Appendix: Scenario Lab history");
        if (labs.isEmpty()) {
            pdf.paragraph("No Scenario Lab attempts for this repository yet.");
        } else {
            pdf.keyValues(labs.stream().map(l -> new String[] { "Lab #" + l.number(), String.join(", ",
                    l.roles().stream().map(role -> role.label()).toList()) + "  ·  " + l.seniority().label() + "  ·  " + l.scenariosCompleted()
                    + " of " + l.scenarioCount() + " scenarios  ·  " + Words.date(l.completedAt()) }).toList());
        }
    }

    private static void dimension(ReportPdf pdf, int n, DimensionReview d) {
        pdf.subheading(n + ".  " + d.name());
        if (d.applicability() == Applicability.NOT_APPLICABLE) {
            pdf.label("Not applicable");
            pdf.paragraph(d.summary());
            return;
        }
        pdf.label(Words.of(d.assessment()) + "  ·  " + Words.of(d.confidence()) + " confidence");
        pdf.paragraph(d.summary());
        for (var s : d.strengths()) {
            pdf.labeled("Strength: " + s.title() + ".", s.description());
        }
        for (Concern c : d.concerns()) {
            pdf.labeled("Concern (" + Words.of(c.severity()).toLowerCase() + "): " + c.title() + ".", c.description());
            pdf.labeled("Why it matters.", c.whyItMatters());
            pdf.labeled("Impact.", c.engineeringImpact());
            scale(pdf, c.scaleImpact());
            pdf.labeled("Recommendation.", c.recommendation());
            pdf.labeled("Evidence.", Words.evidence(c.evidence()));
        }
        for (var t : d.tradeoffs()) {
            pdf.labeled("Trade-off (" + Words.of(t.assessment()).toLowerCase() + "): " + t.decision() + ".",
                    "Benefit: " + t.benefit() + " Cost: " + t.cost());
        }
        pdf.list("Advice for you", d.personalizedAdvice());
    }

    private static void scale(ReportPdf pdf, ScaleImpact s) {
        if (s == null) {
            return;
        }
        pdf.labeled("Today.", s.currentScale());
        pdf.labeled("At 10×.", s.tenX());
        pdf.labeled("At 100×.", s.hundredX());
        pdf.labeled("At large scale.", s.largeScale());
    }

    private static String[] area(String name, ScaleArea a) {
        return new String[] { name, Words.of(a.assessment()) + (a.concerns().isEmpty() ? "" : ": " + String.join("; ", a.concerns())) };
    }
}
