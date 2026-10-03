package com.engineeringlens.analysis.profile;

import java.util.List;

import com.engineeringlens.analysis.common.Confidence;

/**
 * What kind of project a repository is, built only from deterministic evidence: the file inventory
 * plus a small set of manifest and config files. Every detection carries the evidence behind it.
 * Versioned so stored profiles stay readable when this model evolves.
 */
public record RepositoryProfile(
        int profileSchemaVersion,
        Project project,
        InventorySummary inventory,
        /** Share of relevant files per language. File counts, not lines of code. */
        List<LanguageShare> languages,
        List<Detection> frameworks,
        List<Detection> databases,
        /** ORMs, migration tools and similar persistence tooling. */
        List<Detection> persistence,
        List<Detection> packageManagers,
        List<ManifestSummary> manifests,
        Testing testing,
        Deployment deployment,
        Configuration configuration,
        Documentation documentation,
        List<StructureSignal> structureSignals,
        List<String> entrypoints,
        /** Manifest/config files the profiler wanted to read but couldn't, and why. */
        List<UnreadFile> unreadFiles) {

    public record Project(String name, String owner, String defaultBranch, String visibility, String commitSha,
            boolean commitPinnedAtImport) {
    }

    public record InventorySummary(int totalFiles, int relevantFiles, int ignoredFiles, long totalBytes) {
    }

    public record LanguageShare(String name, int fileCount, double percentage) {
    }

    public record Detection(String name, Confidence confidence, List<String> evidence) {
    }

    /** declaredDependencies is null when the manifest format isn't counted reliably. */
    public record ManifestSummary(String path, String ecosystem, Integer declaredDependencies) {
    }

    /** Whether tests appear to exist. Says nothing about coverage, which can't be known from file names. */
    public record Testing(boolean testsDetected, int testFileCount, double testToRelevantRatio, List<Detection> frameworks,
            List<String> testDirectories, List<String> sampleTestFiles) {
    }

    /** What deployment-related files exist. A Dockerfile doesn't mean the project is deployed. */
    public record Deployment(boolean dockerfile, boolean compose, boolean ci, List<String> dockerfiles, List<String> composeFiles,
            List<Detection> ciProviders, List<String> deploymentConfigs) {
    }

    public record Configuration(boolean envExample, List<String> envExampleFiles, List<String> configFiles) {
    }

    public record Documentation(boolean readme, String readmePath, boolean docsDirectory, List<String> apiSpecs,
            List<String> architectureDocs) {
    }

    public record StructureSignal(String signal, List<String> paths) {
    }

    public record UnreadFile(String path, String reason) {
    }
}
