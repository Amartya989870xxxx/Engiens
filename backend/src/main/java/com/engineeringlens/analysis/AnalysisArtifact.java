package com.engineeringlens.analysis;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A completed run's outputs as JSON: profile, signals, and the context manifest (never file contents). */
@Entity
@Table(name = "analysis_artifacts")
public class AnalysisArtifact {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "profile_json", nullable = false)
    private String profileJson;

    @Column(name = "signals_json", nullable = false)
    private String signalsJson;

    @Column(name = "context_manifest_json", nullable = false)
    private String contextManifestJson;

    protected AnalysisArtifact() {
    }

    AnalysisArtifact(UUID runId, String profileJson, String signalsJson, String contextManifestJson) {
        this.runId = runId;
        this.profileJson = profileJson;
        this.signalsJson = signalsJson;
        this.contextManifestJson = contextManifestJson;
    }

    public String getProfileJson() { return profileJson; }
    public String getSignalsJson() { return signalsJson; }
    public String getContextManifestJson() { return contextManifestJson; }
}
