package com.engineeringlens.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextBuilder;
import com.engineeringlens.analysis.context.ContextManifest;
import com.engineeringlens.analysis.context.ContextProperties;
import com.engineeringlens.analysis.context.ContextSelector;
import com.engineeringlens.analysis.context.DeveloperProfile;
import com.engineeringlens.analysis.deterministic.AnalysisInput;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.deterministic.DeterministicAnalyzer;
import com.engineeringlens.analysis.profile.RepositoryProfile;
import com.engineeringlens.analysis.profile.RepositoryProfiler;
import com.engineeringlens.analysis.source.RepositorySourceReader;
import com.engineeringlens.analysis.source.RunFileCache;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.repository.RepoFileRepository;
import com.engineeringlens.repository.RepositoryStatus;
import com.engineeringlens.repository.RepositoryVisibility;
import com.engineeringlens.user.UserProfileRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Prepares an imported repository for review: profile (4A) → select files (4C) → fetch them →
 * deterministic signals (4B) → assemble the provider-neutral context (4C). No AI is involved.
 * Runs within the request for now; the run record and its status are ready for a background job.
 */
@Service
public class AnalysisPreparationService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPreparationService.class);

    private final ImportedRepoRepository repositories;
    private final RepoFileRepository files;
    private final UserProfileRepository profiles;
    private final AnalysisRunRepository runs;
    private final AnalysisArtifactRepository artifacts;
    private final RepositorySourceReader sources;
    private final RepositoryProfiler profiler;
    private final DeterministicAnalyzer analyzer;
    private final ContextSelector selector;
    private final ContextBuilder builder;
    private final ContextProperties limits;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public AnalysisPreparationService(ImportedRepoRepository repositories, RepoFileRepository files, UserProfileRepository profiles,
            AnalysisRunRepository runs, AnalysisArtifactRepository artifacts, RepositorySourceReader sources,
            RepositoryProfiler profiler, DeterministicAnalyzer analyzer, ContextSelector selector, ContextBuilder builder,
            ContextProperties limits, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.repositories = repositories;
        this.files = files;
        this.profiles = profiles;
        this.runs = runs;
        this.artifacts = artifacts;
        this.sources = sources;
        this.profiler = profiler;
        this.analyzer = analyzer;
        this.selector = selector;
        this.builder = builder;
        this.limits = limits;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public AnalysisRunResponse prepare(UUID userId, UUID repositoryId) {
        ImportedRepo repo = ownedRepository(userId, repositoryId);
        if (repo.getStatus() != RepositoryStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY",
                    "Import the repository successfully before preparing it for review.");
        }

        // Same pinned snapshot, same versions, same limits: the result would be identical, so reuse it.
        if (repo.getCommitSha() != null) {
            Optional<AnalysisRun> same = runs
                    .findFirstByRepositoryIdAndStatusAndCommitShaAndProfileSchemaVersionAndRulesVersionAndContextSchemaVersionAndConfigFingerprintOrderByCreatedAtDesc(
                            repo.getId(), AnalysisRunStatus.COMPLETED, repo.getCommitSha(), RepositoryProfiler.SCHEMA_VERSION,
                            DeterministicAnalyzer.RULES_VERSION, AnalysisContext.SCHEMA_VERSION, limits.fingerprint());
            if (same.isPresent()) {
                return toResponse(same.get());
            }
        }

        AnalysisRun run = runs.save(new AnalysisRun(repo.getId(), userId, RepositoryProfiler.SCHEMA_VERSION,
                DeterministicAnalyzer.RULES_VERSION, AnalysisContext.SCHEMA_VERSION, limits.fingerprint()));
        run.start();
        run = runs.save(run);
        log.info("Analysis preparation started: run={} repository={}/{}", run.getId(), repo.getGithubOwner(), repo.getGithubRepoName());
        try {
            Prepared prepared = buildContext(userId, repo);
            AnalysisContext context = prepared.context();
            ContextManifest.Stats stats = context.manifest().stats();
            AnalysisRun finished = run;
            AnalysisArtifact artifact = new AnalysisArtifact(run.getId(), json.writeValueAsString(context.profile()),
                    json.writeValueAsString(context.analysis()), json.writeValueAsString(context.manifest()));
            transaction.executeWithoutResult(tx -> {
                artifacts.save(artifact);
                finished.complete(context.manifest().commitSha(), prepared.filesFetched(), prepared.bytesFetched(),
                        context.analysis().signals().size(), stats.filesSelected(), stats.includedBytes());
                runs.save(finished);
            });
            log.info("Analysis preparation completed: run={} durationMs={} filesFetched={} bytesFetched={} signals={} "
                    + "contextFiles={} contextBytes={}", run.getId(), run.getDurationMs(), prepared.filesFetched(),
                    prepared.bytesFetched(), context.analysis().signals().size(), stats.filesSelected(), stats.includedBytes());
            return toResponse(run, artifact);
        } catch (ApiException e) {
            fail(run, e.getMessage());
            log.warn("Analysis preparation failed: run={} code={}", run.getId(), e.getCode());
            throw e;
        } catch (RuntimeException e) {
            fail(run, "Preparing this repository failed unexpectedly. Please try again.");
            log.error("Analysis preparation failed unexpectedly: run={}", run.getId(), e);
            throw e;
        }
    }

    /** The full context plus fetch counts for the run record. */
    record Prepared(AnalysisContext context, int filesFetched, long bytesFetched) {
    }

    /** The pipeline itself. Package-visible so tests can inspect the in-memory context, contents included. */
    Prepared buildContext(UUID userId, ImportedRepo repo) {
        List<InventoryFile> inventory = files.findByRepositoryIdOrderByPathAsc(repo.getId()).stream()
                .map(f -> new InventoryFile(f.getPath(), f.getLanguage(), f.getSizeBytes(), f.isIgnored(), f.getIgnoreReason()))
                .toList();
        RunFileCache cache = new RunFileCache(sources.open(userId, repo.getGithubOwner(), repo.getGithubRepoName(),
                repo.getDefaultBranch(), repo.getVisibility() == RepositoryVisibility.PRIVATE, repo.getCommitSha()));

        // 4A: profile from the inventory plus a few manifest/config files.
        long t0 = System.nanoTime();
        SourceTexts profileTexts = new SourceTexts();
        for (String path : RepositoryProfiler.filesToRead(inventory, limits.profileMaxFiles(), limits.profileMaxFileBytes())) {
            RunFileCache.Fetched f = cache.fetch(path);
            if (f.ok()) {
                profileTexts.put(path, f.text());
            } else {
                profileTexts.markUnread(path, f.skipReason());
            }
        }
        RepositoryProfile profile = profiler.profile(new RepositoryProfiler.ProjectFacts(repo.getGithubRepoName(),
                repo.getGithubOwner(), repo.getDefaultBranch(), repo.getVisibility().name(), cache.commitSha(),
                cache.pinnedAtImport()), inventory, profileTexts);
        log.info("Repository profiling completed: files={} manifestsRead={} ms={}", inventory.size(), profileTexts.all().size(),
                (System.nanoTime() - t0) / 1_000_000);

        // 4C (selection): choose files from paths and sizes, then download only those.
        ContextSelector.Selection selection = selector.select(inventory, limits);
        ContextBuilder.Contents contents = builder.fetch(selection, cache, limits);

        // 4B: rules over the inventory, the profile and every text fetched in this run.
        Map<String, String> ruleTexts = new LinkedHashMap<>(profileTexts.all());
        ruleTexts.putAll(contents.fullTexts().all());
        DeterministicAnalysis analysis = analyzer.analyze(new AnalysisInput(inventory, profile, SourceTexts.of(ruleTexts)));
        log.info("Deterministic analysis completed: rules={} signals={}", analysis.rulesRun().size(), analysis.signals().size());

        // 4C (assembly): personalisation is attached only now, after all evidence exists.
        AnalysisContext context = builder.assemble(profile, analysis, selection, contents, developer(userId), cache.commitSha(),
                cache.pinnedAtImport(), limits);
        return new Prepared(context, cache.filesFetched(), cache.bytesFetched());
    }

    public AnalysisRunResponse latest(UUID userId, UUID repositoryId) {
        ImportedRepo repo = ownedRepository(userId, repositoryId);
        return runs.findFirstByRepositoryIdOrderByCreatedAtDesc(repo.getId()).map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND",
                        "This repository hasn't been prepared for review yet."));
    }

    public AnalysisRunResponse get(UUID userId, UUID runId) {
        return runs.findByIdAndUserId(runId, userId).map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "We couldn't find that analysis."));
    }

    private ImportedRepo ownedRepository(UUID userId, UUID repositoryId) {
        return repositories.findByIdAndUserId(repositoryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository."));
    }

    private DeveloperProfile developer(UUID userId) {
        return profiles.findById(userId).map(DeveloperProfile::from).orElse(null);
    }

    private void fail(AnalysisRun run, String reason) {
        run.fail(reason);
        runs.save(run);
    }

    private AnalysisRunResponse toResponse(AnalysisRun run) {
        return toResponse(run, run.getStatus() == AnalysisRunStatus.COMPLETED ? artifacts.findById(run.getId()).orElse(null) : null);
    }

    private AnalysisRunResponse toResponse(AnalysisRun run, AnalysisArtifact artifact) {
        RepositoryProfile profile = artifact == null ? null : json.readValue(artifact.getProfileJson(), RepositoryProfile.class);
        DeterministicAnalysis analysis = artifact == null ? null : json.readValue(artifact.getSignalsJson(), DeterministicAnalysis.class);
        ContextManifest manifest = artifact == null ? null : json.readValue(artifact.getContextManifestJson(), ContextManifest.class);
        return new AnalysisRunResponse(run.getId(), run.getRepositoryId(), run.getStatus(), run.getCommitSha(),
                run.getProfileSchemaVersion(), run.getRulesVersion(), run.getContextSchemaVersion(), run.getFailureReason(),
                run.getCreatedAt(), run.getCompletedAt(), run.getDurationMs(),
                new AnalysisRunResponse.Stats(run.getFilesFetched(), run.getBytesFetched(), run.getSignalCount(),
                        run.getContextFileCount(), run.getContextBytes()),
                profile, analysis, manifest);
    }
}
