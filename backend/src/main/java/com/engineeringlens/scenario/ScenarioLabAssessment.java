package com.engineeringlens.scenario;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The lab's final, personalised assessment (validated JSON), written once when the lab completes. */
@Entity
@Table(name = "scenario_lab_assessments")
public class ScenarioLabAssessment {

    @Id
    @Column(name = "lab_id")
    private UUID labId;

    @Column(name = "assessment_json", nullable = false)
    private String assessmentJson;

    private String provider;

    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScenarioLabAssessment() {
    }

    public ScenarioLabAssessment(UUID labId, String assessmentJson, String provider, String model) {
        this.labId = labId;
        this.assessmentJson = assessmentJson;
        this.provider = provider;
        this.model = model;
        this.createdAt = Instant.now();
    }

    public UUID getLabId() { return labId; }
    public String getAssessmentJson() { return assessmentJson; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Instant getCreatedAt() { return createdAt; }
}
