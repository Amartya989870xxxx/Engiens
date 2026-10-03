package com.engineeringlens.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.github.GitHubRepoDetails;
import com.engineeringlens.github.GitHubRepoUrl;
import com.engineeringlens.github.GitHubRepositoryReader;
import com.engineeringlens.github.GitHubRepositoryReader.RemoteRepository;
import com.engineeringlens.github.GitHubTree;

/**
 * Turns a GitHub repository into an imported repository with a file inventory that later
 * analysis can work from. Runs within the request: it costs two or three GitHub calls and one
 * batched insert, typically a few seconds. (Analysis, which is much slower, will run as a job.)
 */
@Service
public class RepositoryImportService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryImportService.class);

    /** Every file and submodule, ignored or not. Above this we refuse rather than store a partial picture. */
    static final int MAX_TOTAL_FILES = 20_000;
    /** Files that would actually be analysed. Keeps future analysis cost and time bounded. */
    static final int MAX_RELEVANT_FILES = 3_000;

    private final GitHubRepositoryReader github;
    private final ImportedRepoRepository repositories;
    private final RepoFileRepository files;
    private final TransactionTemplate transaction;

    public RepositoryImportService(GitHubRepositoryReader github, ImportedRepoRepository repositories,
            RepoFileRepository files, PlatformTransactionManager transactionManager) {
        this.github = github;
        this.repositories = repositories;
        this.files = files;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public RepositoryResponse importRepository(UUID userId, String url) {
        GitHubRepoUrl.OwnerAndName ref = GitHubRepoUrl.parse(url);

        // Idempotent: a repository this user already imported is returned as-is, without calling GitHub.
        Optional<ImportedRepo> existing = repositories
                .findByUserIdAndGithubOwnerIgnoreCaseAndGithubRepoNameIgnoreCase(userId, ref.owner(), ref.name());
        if (existing.isPresent() && existing.get().getStatus() == RepositoryStatus.READY) {
            return toResponse(existing.get());
        }

        // Access check + metadata. Nothing is stored if the repository doesn't exist or isn't accessible.
        RemoteRepository remote = github.read(userId, ref.owner(), ref.name());
        GitHubRepoDetails d = remote.details();

        ImportedRepo repo = existing.orElseGet(() -> new ImportedRepo(userId));
        repo.startImport(d.owner().login(), d.name(), d.htmlUrl(), d.description(), d.defaultBranch(), d.language(),
                d.privateRepo() ? RepositoryVisibility.PRIVATE : RepositoryVisibility.PUBLIC, d.stars(), d.forks());
        try {
            repo = repositories.saveAndFlush(repo);
        } catch (DataIntegrityViolationException e) {
            // Two imports of the same repository raced; the unique constraint let only one row exist.
            return repositories.findByUserIdAndGithubOwnerIgnoreCaseAndGithubRepoNameIgnoreCase(userId, d.owner().login(), d.name())
                    .map(this::toResponse)
                    .orElseThrow(() -> e);
        }

        try {
            List<FileClassifier.Classification> inventory = classify(github.readTree(remote));
            saveInventory(repo, inventory);
        } catch (ApiException e) {
            markFailed(repo, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Repository import failed for {}/{}", d.owner().login(), d.name(), e);
            markFailed(repo, "Import failed unexpectedly. Please try again.");
            throw e;
        }
        return toResponse(repo);
    }

    @Transactional(readOnly = true)
    public RepositoryResponse get(UUID userId, UUID repositoryId) {
        return toResponse(repositories.findByIdAndUserId(repositoryId, userId).orElseThrow(RepositoryImportService::notFound));
    }

    @Transactional(readOnly = true)
    public List<RepositoryListItem> list(UUID userId) {
        return repositories.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(RepositoryListItem::from).toList();
    }

    /** Validates GitHub's tree, classifies every file, and enforces the size limits. */
    static List<FileClassifier.Classification> classify(GitHubTree tree) {
        if (tree == null || tree.tree() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REPOSITORY_TREE_INVALID",
                    "GitHub returned an unreadable file list for this repository. Please try again.");
        }
        // GitHub stops listing very large trees; importing what it did list would silently drop files.
        if (tree.truncated()) {
            throw tooLarge();
        }
        List<FileClassifier.Classification> inventory = new ArrayList<>();
        int relevant = 0;
        for (GitHubTree.Entry entry : tree.tree()) {
            if (entry == null || entry.path() == null || entry.type() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REPOSITORY_TREE_INVALID",
                        "GitHub returned an unreadable file list for this repository. Please try again.");
            }
            FileClassifier.Classification c = switch (entry.type()) {
                case "blob" -> FileClassifier.classify(entry.path(), entry.size() == null ? 0 : entry.size());
                case "commit" -> submodule(entry.path());
                default -> null; // "tree" = folder; folders are implied by file paths
            };
            if (c == null) {
                continue;
            }
            inventory.add(c);
            if (!c.ignored()) {
                relevant++;
            }
            if (inventory.size() > MAX_TOTAL_FILES || relevant > MAX_RELEVANT_FILES) {
                throw tooLarge();
            }
        }
        return inventory;
    }

    private static FileClassifier.Classification submodule(String path) {
        FileClassifier.Classification c = FileClassifier.classify(path, 0);
        return new FileClassifier.Classification(c.path(), c.fileName(), null, null, 0, true, "Git submodule");
    }

    /** Replaces the file list and marks the repository READY in one transaction: all or nothing. */
    private void saveInventory(ImportedRepo repo, List<FileClassifier.Classification> inventory) {
        int relevant = (int) inventory.stream().filter(c -> !c.ignored()).count();
        transaction.executeWithoutResult(status -> {
            files.deleteAllForRepository(repo.getId());
            files.saveAll(inventory.stream().map(c -> new RepoFile(repo.getId(), c)).toList());
            repo.markReady(inventory.size(), relevant, inventory.size() - relevant);
            repositories.save(repo);
        });
    }

    private void markFailed(ImportedRepo repo, String reason) {
        repo.markFailed(reason);
        repositories.save(repo);
    }

    private RepositoryResponse toResponse(ImportedRepo r) {
        List<RepositoryResponse.Count> languages = counts(files.relevantLanguages(r.getId()));
        List<RepositoryResponse.Count> ignored = counts(files.ignoreReasons(r.getId()));
        return new RepositoryResponse(r.getId(), r.getGithubOwner(), r.getGithubRepoName(), r.getGithubUrl(),
                r.getDescription(), r.getDefaultBranch(), r.getPrimaryLanguage(), r.getVisibility(), r.getStars(),
                r.getForks(), r.getFileCount(), r.getRelevantFileCount(), r.getIgnoredFileCount(), r.getStatus(),
                r.getFailureReason(), r.getUpdatedAt(), languages, ignored);
    }

    private static List<RepositoryResponse.Count> counts(List<RepoFileRepository.LabelCount> rows) {
        return rows.stream().map(row -> new RepositoryResponse.Count(row.getLabel(), row.getCount())).toList();
    }

    static ApiException tooLarge() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REPOSITORY_TOO_LARGE",
                "This repository is larger than the current Engiens review limit. Try a smaller repository for now.");
    }

    private static ApiException notFound() {
        // Same answer for "doesn't exist" and "belongs to someone else", so ids can't be probed.
        return new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository.");
    }
}
