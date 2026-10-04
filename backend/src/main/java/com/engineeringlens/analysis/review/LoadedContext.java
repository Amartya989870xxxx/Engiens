package com.engineeringlens.analysis.review;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.DeveloperProfile;

/**
 * An analysis context rebuilt for review, plus what the validator needs to check evidence.
 *
 * @param repositoryPaths  every file path in the imported inventory (evidence may only cite these)
 * @param includedLines    line count of each file whose content the reviewer saw (line numbers must fit)
 * @param unavailableFiles selected files whose content couldn't be re-fetched or no longer matched its hash
 * @param developer        the developer's current profile, for personalisation only
 */
public record LoadedContext(AnalysisContext context, Set<String> repositoryPaths, Map<String, Integer> includedLines,
        List<String> unavailableFiles, DeveloperProfile developer) {
}
