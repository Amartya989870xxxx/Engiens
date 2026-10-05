package com.engineeringlens.export;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.common.pdf.ReportPdf;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.WorkMode;
import com.engineeringlens.scenario.execution.CheckResult;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.history.LabAssessmentResponse;
import com.engineeringlens.scenario.history.LabAssessmentResponse.ScenarioResult;
import com.engineeringlens.scenario.history.ScenarioHistoryService;
import com.engineeringlens.scenario.model.LabAssessment;
import com.engineeringlens.scenario.model.ScenarioEvaluation;

/**
 * A completed Scenario Lab as a PDF, typeset from the persisted assessment and attempts. It never calls the AI.
 * Each scenario starts on its own page so a 20-scenario lab stays readable.
 */
@Service
public class LabAssessmentPdfService {

    private final ScenarioHistoryService history;

    public LabAssessmentPdfService(ScenarioHistoryService history) {
        this.history = history;
    }

    public ReviewPdfService.Pdf render(UUID userId, UUID labId) {
        LabAssessmentResponse lab = history.assessment(userId, labId); // 404 for someone else's lab, 409 if not completed
        try {
            byte[] bytes = ReportPdf.build("Scenario Lab Assessment: " + lab.repositoryName(),
                    "Engiens  ·  Scenario Lab #" + lab.number() + "  ·  " + lab.repositoryName(), pdf -> write(pdf, lab));
            return new ReviewPdfService.Pdf("engiens-scenario-lab-" + Words.slug(lab.repositoryName()) + "-" + lab.number() + ".pdf", bytes);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF_GENERATION_FAILED", "The PDF couldn't be created. Please try again.");
        }
    }

    private static void write(ReportPdf pdf, LabAssessmentResponse lab) {
        LabAssessment a = lab.assessment();
        List<String[]> meta = new ArrayList<>();
        meta.add(new String[] { "Repository", lab.repositoryUrl() == null ? lab.repositoryName() : lab.repositoryUrl() });
        meta.add(new String[] { "Commit", lab.commitSha() });
        meta.add(new String[] { "Roles", String.join(", ", lab.roles().stream().map(r -> r.label()).toList()) });
        meta.add(new String[] { "Seniority", lab.seniority().label() });
        meta.add(new String[] { "Scenarios", lab.scenarios().size() + " of " + lab.scenariosGenerated() + " completed"
                + (lab.scenariosGenerated() < lab.scenarioCount() ? " (" + lab.scenariosGenerated() + " of " + lab.scenarioCount() + " generated)" : "") });
        meta.add(new String[] { "Completed", Words.date(lab.completedAt()) });
        if (a.personalizedFor() != null) {
            meta.add(new String[] { "Learning points for", a.personalizedFor() });
        }
        pdf.cover("Scenario Lab #" + lab.number(), "Scenario Lab Assessment", lab.repositoryName(), meta);

        pdf.section("Overall assessment");
        pdf.label(Words.of(a.overallAssessment().confidence()) + " confidence");
        pdf.paragraph(a.overallAssessment().summary());
        pdf.labeled("Against the target level.", a.overallAssessment().engineeringLevel());
        pdf.list("Strengths", a.strengths());
        pdf.list("Growth areas", a.growthAreas());

        pdf.section("Scenarios at a glance");
        pdf.keyValues(lab.scenarios().stream().map(s -> new String[] { "Scenario " + s.position(),
                s.title() + "  ·  " + verdict(s.evaluation()) + "  ·  " + checks(s.runResult(), s.executionCapability()) }).toList());

        for (ScenarioResult s : lab.scenarios()) {
            pdf.newPage();
            scenario(pdf, s);
        }

        pdf.newPage();
        pdf.section("Learning recommendations");
        if (a.learningRecommendations().isEmpty()) {
            pdf.paragraph("No personalised recommendations were generated for this lab.");
        }
        for (var r : a.learningRecommendations()) {
            pdf.subheading(r.topic());
            pdf.labeled("Why.", r.why());
            pdf.labeled("In this project.", r.connectionToProject());
        }
        if (!a.limitations().isEmpty()) {
            pdf.section("Limitations");
            pdf.bullets(a.limitations());
        }
        pdf.paragraph("Scenario Lab evaluates engineering judgement on problems drawn from this repository. It is not a measure of "
                + "industry readiness or of anyone's professional worth.");
    }

    private static void scenario(ReportPdf pdf, ScenarioResult s) {
        pdf.section("Scenario " + s.position() + "  ·  " + s.title());
        pdf.label(Words.of(s.category()) + "  ·  " + Words.of(s.difficulty()) + "  ·  " + s.role().label() + "  ·  answered in "
                + (s.mode() == WorkMode.CODE ? "code" : "approach") + " mode");
        pdf.paragraph(s.document().summary());
        pdf.labeled("Incident.", s.document().incident());
        pdf.labeled("Task.", s.document().task());

        pdf.subheading("Your submission");
        if (s.mode() == WorkMode.CODE || s.submittedApproach() == null) {
            s.submittedFiles().forEach(f -> pdf.code(f.path(), f.content()));
        }
        pdf.labeled("Your approach.", s.submittedApproach());
        if (s.mode() == WorkMode.APPROACH && !s.submittedFiles().isEmpty()) {
            pdf.label("Code at submission (secondary evidence)");
            s.submittedFiles().forEach(f -> pdf.code(f.path(), f.content()));
        }

        if (s.executionCapability() == ExecutionCapability.CODE) {
            pdf.subheading("Objective results");
            RunResult run = s.runResult();
            if (run == null) {
                pdf.paragraph("The checks couldn't be run when this was submitted.");
            } else {
                pdf.label(Words.of(run.status()) + "  ·  " + run.passed() + " of " + run.total() + " checks passed");
                if (run.message() != null) {
                    pdf.paragraph(run.message());
                }
                pdf.bullets(run.checks().stream().map(LabAssessmentPdfService::check).toList());
            }
        }

        ScenarioEvaluation e = s.evaluation();
        pdf.subheading("Evaluation");
        if (e == null) {
            pdf.paragraph("No evaluation was recorded.");
            return;
        }
        pdf.label(Words.of(e.verdict()) + "  ·  " + Words.of(e.confidence()) + " confidence");
        pdf.paragraph(e.assessment());
        pdf.list("What you got right", e.whatWasCorrect());
        pdf.list("What you missed", e.whatWasMissed());
        pdf.labeled("Root cause.", e.rootCause());
        pdf.labeled("Engineering judgement.", e.engineeringJudgment());
        pdf.list("Trade-offs", e.tradeoffs());
        pdf.labeled("At larger scale.", e.scaleImpact());
        pdf.labeled("Regression risk.", e.regressionRisk());
        pdf.labeled("Testing.", e.testingAssessment());
        pdf.labeled("Recommended fix.", e.recommendedFix());
        pdf.labeled("A stronger approach.", e.referenceApproach());
        if (s.reference() != null) {
            pdf.labeled("Reference reasoning.", s.reference().referenceReasoning());
        }
        pdf.list("What to learn", s.learningPoints());
    }

    private static String check(CheckResult c) {
        return (c.passed() ? "Passed: " : "Failed: ") + c.name() + (c.message() == null ? "" : " (" + c.message() + ")");
    }

    private static String verdict(ScenarioEvaluation e) {
        return e == null ? "not evaluated" : Words.of(e.verdict());
    }

    private static String checks(RunResult run, ExecutionCapability capability) {
        if (capability != ExecutionCapability.CODE) {
            return "approach";
        }
        return run == null ? "not run" : run.passed() + "/" + run.total() + " checks";
    }
}
