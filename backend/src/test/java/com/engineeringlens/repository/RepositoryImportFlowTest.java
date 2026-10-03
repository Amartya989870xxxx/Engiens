package com.engineeringlens.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.github.GitHubRepoDetails;
import com.engineeringlens.github.GitHubRepositoryReader;
import com.engineeringlens.github.GitHubRepositoryReader.RemoteRepository;
import com.engineeringlens.github.GitHubRepositoryReader.RepositorySnapshot;
import com.engineeringlens.github.GitHubTree;
import com.jayway.jsonpath.JsonPath;

/** Full HTTP → service → database flow; only GitHub is faked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepositoryImportFlowTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GitHubRepositoryReader github;

    private static RemoteRepository remote(String owner, String name, boolean privateRepo, Long installationId) {
        return new RemoteRepository(new GitHubRepoDetails(name, new GitHubRepoDetails.Owner(owner), "A demo project", "main",
                "TypeScript", privateRepo, 12, 2, "https://github.com/" + owner + "/" + name), installationId);
    }

    private static GitHubTree tree(GitHubTree.Entry... entries) {
        return new GitHubTree(false, List.of(entries));
    }

    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static RepositorySnapshot snapshot(GitHubTree tree) {
        return new RepositorySnapshot(COMMIT, tree);
    }

    private static GitHubTree.Entry file(String path, long size) {
        return new GitHubTree.Entry(path, "blob", size);
    }

    private static final GitHubTree SMALL_PROJECT = tree(
            new GitHubTree.Entry("src", "tree", null),
            file("src/api/orders.ts", 2_400),
            file("src/App.tsx", 1_200),
            file("package.json", 900),
            file("Dockerfile", 300),
            file("node_modules/react/index.js", 14_000),
            file("docs/logo.png", 50_000));

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private ResultActions importRepo(String auth, String url) throws Exception {
        return mvc.perform(post("/api/repositories/import").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"));
    }

    private String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void importsAPublicRepositoryWithItsFileInventory() throws Exception {
        String auth = register("import@example.com");
        RemoteRepository remote = remote("asha", "orders-api", false, null);
        when(github.read(any(), eq("asha"), eq("orders-api"))).thenReturn(remote);
        when(github.readSnapshot(remote)).thenReturn(snapshot(SMALL_PROJECT));

        String id = idOf(importRepo(auth, "https://github.com/asha/orders-api")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("asha"))
                .andExpect(jsonPath("$.name").value("orders-api"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.defaultBranch").value("main"))
                .andExpect(jsonPath("$.stars").value(12))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.fileCount").value(6))
                .andExpect(jsonPath("$.relevantFileCount").value(4))
                .andExpect(jsonPath("$.ignoredFileCount").value(2))
                .andExpect(jsonPath("$.languages[0].label").value("TypeScript"))
                .andExpect(jsonPath("$.languages[0].count").value(2))
                .andExpect(jsonPath("$.ignoredReasons[?(@.label == 'Dependency directory')].count").value(1))
                .andExpect(jsonPath("$.ignoredReasons[?(@.label == 'Image asset')].count").value(1))
                // Our own field names only: GitHub's JSON shape never leaks through.
                .andExpect(jsonPath("$.stargazers_count").doesNotExist())
                .andExpect(jsonPath("$.default_branch").doesNotExist()));

        mvc.perform(get("/api/repositories/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get("/api/repositories").header("Authorization", auth))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].name").value("orders-api"));
    }

    @Test
    void importingTheSameRepositoryAgainReturnsTheSameRecord() throws Exception {
        String auth = register("twice@example.com");
        RemoteRepository remote = remote("asha", "twice", false, null);
        when(github.read(any(), eq("asha"), eq("twice"))).thenReturn(remote);
        when(github.readSnapshot(remote)).thenReturn(snapshot(SMALL_PROJECT));

        String first = idOf(importRepo(auth, "https://github.com/asha/twice"));
        // Different casing and a trailing path still mean the same repository.
        String second = idOf(importRepo(auth, "https://github.com/ASHA/Twice/tree/main"));

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
        verify(github, times(1)).read(any(), any(), any()); // the second import didn't even call GitHub
        mvc.perform(get("/api/repositories").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void usersCannotReadEachOthersRepositories() throws Exception {
        String owner = register("owner@example.com");
        String stranger = register("stranger@example.com");
        RemoteRepository remote = remote("asha", "mine", false, null);
        when(github.read(any(), eq("asha"), eq("mine"))).thenReturn(remote);
        when(github.readSnapshot(remote)).thenReturn(snapshot(SMALL_PROJECT));
        String id = idOf(importRepo(owner, "https://github.com/asha/mine"));

        mvc.perform(get("/api/repositories/" + id).header("Authorization", stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));
        mvc.perform(get("/api/repositories").header("Authorization", stranger))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/repositories/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void privateRepositoriesComeThroughTheConnectedInstallation() throws Exception {
        String auth = register("private@example.com");
        RemoteRepository remote = remote("asha", "secret-app", true, 7L);
        when(github.read(any(), eq("asha"), eq("secret-app"))).thenReturn(remote);
        when(github.readSnapshot(remote)).thenReturn(snapshot(SMALL_PROJECT));

        importRepo(auth, "https://github.com/asha/secret-app")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void oversizedRepositoryFailsVisiblyInsteadOfBeingTruncated() throws Exception {
        String auth = register("huge@example.com");
        RemoteRepository remote = remote("asha", "monorepo", false, null);
        when(github.read(any(), eq("asha"), eq("monorepo"))).thenReturn(remote);
        List<GitHubTree.Entry> entries = new ArrayList<>();
        for (int i = 0; i <= RepositoryImportService.MAX_TOTAL_FILES; i++) {
            entries.add(file("src/file" + i + ".ts", 100));
        }
        when(github.readSnapshot(remote)).thenReturn(snapshot(new GitHubTree(false, entries)));

        importRepo(auth, "https://github.com/asha/monorepo")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REPOSITORY_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(
                        "This repository is larger than the current Engiens review limit. Try a smaller repository for now."));

        // The attempt is recorded as FAILED with the reason, and no files were kept.
        String id = JsonPath.read(mvc.perform(get("/api/repositories").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(get("/api/repositories/" + id).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.startsWith("This repository is larger")))
                .andExpect(jsonPath("$.fileCount").value(0));
    }

    @Test
    void truncatedTreeFromGitHubCountsAsTooLarge() throws Exception {
        String auth = register("truncated@example.com");
        RemoteRepository remote = remote("asha", "giant", false, null);
        when(github.read(any(), eq("asha"), eq("giant"))).thenReturn(remote);
        when(github.readSnapshot(remote)).thenReturn(snapshot(new GitHubTree(true, List.of(file("a.ts", 1)))));

        importRepo(auth, "https://github.com/asha/giant")
                .andExpect(jsonPath("$.code").value("REPOSITORY_TOO_LARGE"));
    }

    @Test
    void failedImportCanBeRetriedIntoTheSameRecord() throws Exception {
        String auth = register("retry@example.com");
        RemoteRepository remote = remote("asha", "flaky", false, null);
        when(github.read(any(), eq("asha"), eq("flaky"))).thenReturn(remote);
        when(github.readSnapshot(remote))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "Could not reach GitHub. Please try again."))
                .thenReturn(snapshot(SMALL_PROJECT));

        importRepo(auth, "https://github.com/asha/flaky").andExpect(status().isBadGateway());
        String failedId = JsonPath.read(mvc.perform(get("/api/repositories").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        // IMPORTING → FAILED, then a retry goes IMPORTING → READY on the same row.
        importRepo(auth, "https://github.com/asha/flaky")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(failedId))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void missingRepositoryCreatesNoRecord() throws Exception {
        String auth = register("missing@example.com");
        when(github.read(any(), eq("asha"), eq("nope")))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository."));

        importRepo(auth, "https://github.com/asha/nope")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("We couldn't find that repository."));
        mvc.perform(get("/api/repositories").header("Authorization", auth)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void noAccessAndRateLimitErrorsPassThroughUnchanged() throws Exception {
        String auth = register("errors@example.com");
        when(github.read(any(), eq("asha"), eq("locked"))).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "REPOSITORY_NO_ACCESS",
                "You don't have access to this repository through your connected GitHub account."));
        when(github.read(any(), eq("asha"), eq("busy"))).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "GITHUB_RATE_LIMITED", "GitHub is temporarily limiting requests. Please try again later."));

        importRepo(auth, "https://github.com/asha/locked").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REPOSITORY_NO_ACCESS"));
        importRepo(auth, "https://github.com/asha/busy").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_RATE_LIMITED"));
    }

    @Test
    void invalidLinksAreRejectedBeforeCallingGitHub() throws Exception {
        String auth = register("invalid@example.com");
        importRepo(auth, "https://gitlab.com/asha/project")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPOSITORY_URL"));
        importRepo(auth, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.url").exists());
        mvc.perform(get("/api/repositories/not-a-uuid").header("Authorization", auth)).andExpect(status().isBadRequest());
        verify(github, never()).read(any(), any(), any());
    }
}
