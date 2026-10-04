package com.engineeringlens.analysis.review;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.AnalysisArtifact;
import com.engineeringlens.analysis.AnalysisArtifactRepository;
import com.engineeringlens.analysis.AnalysisRun;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextBuilder;
import com.engineeringlens.analysis.context.ContextFile;
import com.engineeringlens.analysis.context.ContextManifest;
import com.engineeringlens.analysis.context.DeveloperProfile;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.profile.RepositoryProfile;
import com.engineeringlens.analysis.source.RepositorySourceReader;
import com.engineeringlens.analysis.source.RunFileCache;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.RepoFile;
import com.engineeringlens.repository.RepoFileRepository;
import com.engineeringlens.repository.RepositoryVisibility;
import com.engineeringlens.user.UserProfileRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Rebuilds a stored analysis context for review. Preparation stores no source code, only the manifest
 * with a SHA-256 per included file; here the same files are re-read at the pinned commit, cut exactly as
 * before, and verified against those hashes. Nothing else in the repository is read.
 */
@Component
public class AnalysisContextLoader {

    private static final Logger log = LoggerFactory.getLogger(AnalysisContextLoader.class);

    private final AnalysisArtifactRepository artifacts;
    private final RepoFileRepository files;
    private final RepositorySourceReader sources;
    private final UserProfileRepository profiles;
    private final ObjectMapper json;

    public AnalysisContextLoader(AnalysisArtifactRepository artifacts, RepoFileRepository files, RepositorySourceReader sources,
            UserProfileRepository profiles, ObjectMapper json) {
        this.artifacts = artifacts;
        this.files = files;
        this.sources = sources;
        this.profiles = profiles;
        this.json = json;
    }

    public LoadedContext load(UUID userId, ImportedRepo repo, AnalysisRun run) {
        AnalysisArtifact artifact = artifacts.findById(run.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY", "Prepare this repository for review first."));
        RepositoryProfile profile = json.readValue(artifact.getProfileJson(), RepositoryProfile.class);
        DeterministicAnalysis analysis = json.readValue(artifact.getSignalsJson(), DeterministicAnalysis.class);
        ContextManifest manifest = json.readValue(artifact.getContextManifestJson(), ContextManifest.class);

        RunFileCache cache = new RunFileCache(sources.open(userId, repo.getGithubOwner(), repo.getGithubRepoName(),
                repo.getDefaultBranch(), repo.getVisibility() == RepositoryVisibility.PRIVATE, manifest.commitSha()));
        Map<String, String> contents = new LinkedHashMap<>();
        Map<String, Integer> lines = new HashMap<>();
        List<String> unavailable = new ArrayList<>();
        for (ContextFile f : manifest.files()) {
            if (f.sha256() == null) {
                continue; // skipped or withheld at preparation: never sent
            }
            RunFileCache.Fetched fetched = cache.fetch(f.path());
            String text = fetched.ok() ? ContextBuilder.firstBytes(fetched.text(), (int) f.includedBytes()) : null;
            if (text == null || !ContextBuilder.sha256(text).equals(f.sha256())) {
                unavailable.add(f.path());
                continue;
            }
            contents.put(f.path(), text);
            lines.put(f.path(), text.isEmpty() ? 0 : (int) text.lines().count());
        }
        if (!unavailable.isEmpty()) {
            log.warn("Review context: {} selected files could not be re-read or no longer match their hash", unavailable.size());
        }
        Set<String> paths = files.findByRepositoryIdOrderByPathAsc(repo.getId()).stream().map(RepoFile::getPath)
                .collect(Collectors.toUnmodifiableSet());
        DeveloperProfile developer = profiles.findById(userId).map(DeveloperProfile::from).orElse(manifest.developer());
        AnalysisContext context = new AnalysisContext(manifest.contextSchemaVersion(), profile, analysis, manifest, contents);
        return new LoadedContext(context, paths, lines, unavailable, developer);
    }
}
