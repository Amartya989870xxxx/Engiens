package com.engineeringlens.analysis.context;

import java.util.List;
import java.util.Map;

import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.deterministic.Signal;
import com.engineeringlens.analysis.profile.RepositoryProfile;

/**
 * Everything a reviewer needs, provider-neutral: the repository profile, the deterministic signals,
 * the manifest, and the included file contents (held in memory only). A later AI layer asks this object
 * for evidence per dimension and builds its own prompt; nothing here knows about any model.
 */
public record AnalysisContext(int contextSchemaVersion, RepositoryProfile profile, DeterministicAnalysis analysis,
        ContextManifest manifest, Map<String, String> contents) {

    public static final int SCHEMA_VERSION = 1;

    /** A selected file with the exact text a reviewer may see (null when skipped or withheld). */
    public record EvidenceFile(ContextFile file, String text) {
    }

    public record DimensionEvidence(ReviewDimension dimension, List<EvidenceFile> files, List<Signal> signals) {
    }

    /** "Give me the architecture evidence": files chosen for that dimension, best first, plus its signals. */
    public DimensionEvidence evidence(ReviewDimension dimension) {
        ContextManifest.DimensionContext d = manifest.dimensions().get(dimension);
        Map<String, ContextFile> byPath = new java.util.HashMap<>();
        manifest.files().forEach(f -> byPath.put(f.path(), f));
        List<EvidenceFile> files = d.files().stream()
                .map(p -> new EvidenceFile(byPath.get(p.path()), contents.get(p.path())))
                .toList();
        List<Signal> signals = d.signalIndexes().stream().map(i -> analysis.signals().get(i)).toList();
        return new DimensionEvidence(dimension, files, signals);
    }

    public List<Signal> signals() {
        return analysis.signals();
    }

    public DeveloperProfile developer() {
        return manifest.developer();
    }
}
