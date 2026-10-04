package com.engineeringlens.scenario.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

/**
 * One completed lab in a repository's history: just enough for a compact list. The full assessment is
 * fetched only when it's opened.
 *
 * @param number 1 for the repository's first completed lab, 2 for the next…
 */
public record LabHistoryItem(UUID id, int number, List<ScenarioRole> roles, Seniority seniority, int scenarioCount, int scenariosCompleted,
        String commitSha, UUID reviewId, Instant completedAt) {
}
