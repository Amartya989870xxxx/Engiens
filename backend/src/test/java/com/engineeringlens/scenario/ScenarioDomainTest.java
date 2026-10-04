package com.engineeringlens.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The lab state machine and the scenario/attempt rules, without a database. */
class ScenarioDomainTest {

    private static final UUID USER = UUID.randomUUID();

    private static ScenarioLab lab() {
        ScenarioLab lab = new ScenarioLab(USER, UUID.randomUUID(), null, UUID.randomUUID(), "abc123", List.of(ScenarioRole.BACKEND_ENGINEER),
                Seniority.SDE2, 5);
        ReflectionTestUtils.setField(lab, "id", UUID.randomUUID());
        return lab;
    }

    private static Scenario scenario(UUID labId, ExecutionCapability capability) {
        boolean code = capability == ExecutionCapability.CODE;
        Scenario s = new Scenario(labId, 1, ScenarioRole.BACKEND_ENGINEER, Seniority.SDE2, ScenarioCategory.CONCURRENCY_CONSISTENCY,
                ScenarioDifficulty.ADVANCED, capability, code ? ScenarioLanguage.PYTHON : null, "Prevent duplicate orders", "{}",
                code ? "{\"files\":[]}" : null, code ? "{\"checks\":[]}" : null, "{}", null);
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        return s;
    }

    @Test
    void aLabWalksGeneratingActiveFinalizingCompletedAndThenFreesTheOpenSlot() {
        ScenarioLab lab = lab();
        assertThat(lab.getStatus()).isEqualTo(ScenarioLabStatus.GENERATING);
        assertThat(lab.getActiveUserId()).isEqualTo(USER);

        lab.scenarioReady();
        lab.activate();
        assertThat(lab.getScenariosReady()).isEqualTo(1);
        assertThat(lab.getStatus().open()).isTrue();
        lab.startFinalizing();
        assertThat(lab.getActiveUserId()).isEqualTo(USER); // still the workspace while the assessment is written
        lab.complete();

        assertThat(lab.getStatus()).isEqualTo(ScenarioLabStatus.COMPLETED);
        assertThat(lab.getStatus().open()).isFalse();
        assertThat(lab.getActiveUserId()).isNull();
        assertThat(lab.getCompletedAt()).isNotNull();
    }

    @Test
    void closedLabsAreNeverOpenAndCantBeReopenedOrRecompleted() {
        assertThat(ScenarioLabStatus.COMPLETED.open()).isFalse();
        assertThat(ScenarioLabStatus.FAILED.open()).isFalse();
        assertThat(ScenarioLabStatus.CANCELLED.open()).isFalse();

        ScenarioLab cancelled = lab();
        cancelled.cancel();
        assertThat(cancelled.getActiveUserId()).isNull();
        assertThatThrownBy(cancelled::activate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(cancelled::cancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancelled.fail("X", "x")).isInstanceOf(IllegalStateException.class);

        ScenarioLab failed = lab();
        failed.fail("SCENARIO_GENERATION_FAILED", "Generation failed");
        assertThat(failed.getStatus()).isEqualTo(ScenarioLabStatus.FAILED);
        assertThat(failed.getActiveUserId()).isNull();

        ScenarioLab done = lab();
        done.activate();
        done.startFinalizing();
        done.complete();
        assertThatThrownBy(done::complete).isInstanceOf(IllegalStateException.class); // completing twice is refused
        assertThatThrownBy(done::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLabCantSkipSteps() {
        ScenarioLab lab = lab();
        assertThatThrownBy(lab::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(lab::startFinalizing).isInstanceOf(IllegalStateException.class);
        lab.activate();
        assertThatThrownBy(lab::scenarioReady).isInstanceOf(IllegalStateException.class);
        lab.startFinalizing();
        assertThatThrownBy(lab::cancel).isInstanceOf(IllegalStateException.class); // evaluated work is never thrown away
    }

    @Test
    void anExecutableScenarioOpensInCodeAndSwitchingModesKeepsBothDrafts() {
        Scenario s = scenario(UUID.randomUUID(), ExecutionCapability.CODE);
        assertThat(s.getDraftMode()).isEqualTo(WorkMode.CODE);

        s.saveDraft(WorkMode.CODE, "{\"files\":[{\"path\":\"orders.py\",\"content\":\"x = 1\"}]}", null);
        s.saveDraft(WorkMode.APPROACH, null, "First I would check for a unique constraint.");
        assertThat(s.getDraftMode()).isEqualTo(WorkMode.APPROACH);
        assertThat(s.getDraftFilesJson()).contains("x = 1");
        s.saveDraft(WorkMode.CODE, null, null);
        assertThat(s.getDraftMode()).isEqualTo(WorkMode.CODE);
        assertThat(s.getDraftApproach()).isEqualTo("First I would check for a unique constraint.");
        assertThat(s.getDraftFilesJson()).contains("x = 1");
    }

    @Test
    void anApproachOnlyScenarioCantBeAnsweredWithCode() {
        Scenario s = scenario(UUID.randomUUID(), ExecutionCapability.APPROACH_ONLY);
        assertThat(s.getDraftMode()).isEqualTo(WorkMode.APPROACH);
        assertThatThrownBy(() -> s.saveDraft(WorkMode.CODE, "{}", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codeFieldsAreRequiredExactlyForExecutableScenarios() {
        assertThatThrownBy(() -> new Scenario(UUID.randomUUID(), 1, ScenarioRole.BACKEND_ENGINEER, Seniority.SDE1,
                ScenarioCategory.ERROR_HANDLING, ScenarioDifficulty.FOUNDATIONAL, ExecutionCapability.CODE, ScenarioLanguage.PYTHON, "t",
                "{}", null, null, "{}", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scenario(UUID.randomUUID(), 1, ScenarioRole.CLOUD_ENGINEER, Seniority.SDE1,
                ScenarioCategory.CLOUD_ARCHITECTURE, ScenarioDifficulty.FOUNDATIONAL, ExecutionCapability.APPROACH_ONLY,
                ScenarioLanguage.PYTHON, "t", "{}", "{}", "{}", "{}", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAttemptCopiesTheLabSnapshotAndIsEvaluatedOnce() {
        ScenarioLab lab = lab();
        Scenario s = scenario(lab.getId(), ExecutionCapability.CODE);
        ScenarioAttempt attempt = new ScenarioAttempt(lab, s, WorkMode.CODE, "{\"files\":[]}", "approach", "{\"passed\":4,\"total\":6}");

        assertThat(attempt.getCommitSha()).isEqualTo("abc123");
        assertThat(attempt.getRepositoryId()).isEqualTo(lab.getRepositoryId());
        assertThat(attempt.getSeniority()).isEqualTo(Seniority.SDE2);
        assertThat(attempt.getCategory()).isEqualTo(ScenarioCategory.CONCURRENCY_CONSISTENCY);
        assertThat(attempt.getEvaluationStatus()).isEqualTo(EvaluationStatus.PENDING);

        attempt.evaluationFailed("AI_UNAVAILABLE", "No AI model is available right now.");
        attempt.retryEvaluation();
        attempt.evaluated("{}", "gemini", "m");
        assertThat(attempt.getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThatThrownBy(() -> attempt.evaluated("{}", "gemini", "m")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(attempt::retryEvaluation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAttemptMustBelongToItsLab() {
        ScenarioLab lab = lab();
        Scenario other = scenario(UUID.randomUUID(), ExecutionCapability.CODE);
        assertThatThrownBy(() -> new ScenarioAttempt(lab, other, WorkMode.CODE, "{}", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesAreStoredInTheChosenOrder() {
        RoleListConverter c = new RoleListConverter();
        List<ScenarioRole> roles = List.of(ScenarioRole.CLOUD_ENGINEER, ScenarioRole.BACKEND_ENGINEER);
        assertThat(c.convertToEntityAttribute(c.convertToDatabaseColumn(roles))).isEqualTo(roles);
        assertThat(c.convertToEntityAttribute("")).isEmpty();
    }
}
