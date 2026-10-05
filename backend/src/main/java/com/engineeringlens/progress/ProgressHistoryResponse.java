package com.engineeringlens.progress;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

/** Completed reviews and labs in one timeline, newest first, a page at a time. */
public record ProgressHistoryResponse(List<Item> items, int page, boolean hasMore) {

    public enum Type {
        REVIEW, LAB
    }

    /**
     * One completed review or lab. Review fields: {@code overall}. Lab fields: roles, seniority and the counts.
     *
     * @param id the review run id or the lab id (the link target)
     */
    public record Item(Type type, UUID id, UUID repositoryId, String repositoryName, String commitSha, Instant date, Assessment overall,
            List<ScenarioRole> roles, Seniority seniority, Integer scenariosGenerated, Integer scenariosSubmitted) {
    }
}
