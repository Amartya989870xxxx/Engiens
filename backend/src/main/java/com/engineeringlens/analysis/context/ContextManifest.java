package com.engineeringlens.analysis.context;

import java.util.List;
import java.util.Map;

import com.engineeringlens.analysis.common.ReviewDimension;

/**
 * The persisted description of a context: which commit, which limits, which files for which dimension
 * and why, and how much of each was included. Together with the commit and content hashes it makes a
 * context reproducible without storing anyone's source code.
 */
public record ContextManifest(int contextSchemaVersion, String commitSha, boolean commitPinnedAtImport, ContextProperties limits,
        DeveloperProfile developer, List<ContextFile> files, Map<ReviewDimension, DimensionContext> dimensions, Stats stats) {

    /**
     * @param files         chosen for this dimension, best first, with reasons
     * @param signalIndexes positions in the deterministic analysis's signal list that belong to this dimension
     */
    public record DimensionContext(List<ContextSelector.Pick> files, List<Integer> signalIndexes) {
    }

    public record Stats(int filesConsidered, int filesSelected, int filesIncluded, int filesTruncated, int filesSkipped,
            int filesWithheld, long includedBytes) {
    }
}
