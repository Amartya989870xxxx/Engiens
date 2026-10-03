package com.engineeringlens.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.engineeringlens.analysis.source.RepositorySourceReader;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.github.GitHubRepoDetails;
import com.engineeringlens.github.GitHubRepositoryReader;
import com.engineeringlens.github.GitHubRepositoryReader.RemoteRepository;
import com.engineeringlens.github.GitHubRepositoryReader.RepositorySnapshot;
import com.engineeringlens.github.GitHubTree;
import com.jayway.jsonpath.JsonPath;

/** Import → prepare → read back, through HTTP and the real database. Only GitHub is faked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisFlowTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    /** A unique line of source: if it ever appears in an API response or stored artifact, code leaked. */
    private static final String SOURCE_MARKER = "UNIQUE_SOURCE_MARKER_42";

    private static final Map<String, String> FILES = Map.of(
            "requirements.txt", "fastapi\npsycopg\npytest\n",
            "app/main.py", "from fastapi import FastAPI\napp = FastAPI()  # " + SOURCE_MARKER + "\n",
            "app/services/orders.py", "def place(order):\n    return order\n",
            "tests/test_orders.py", "def test_place():\n    assert True\n",
            "Dockerfile", "FROM python:3.12\n",
            "README.md", "# Orders\n");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GitHubRepositoryReader github;

    @MockitoBean
    RepositorySourceReader sources;

    @Autowired
    AnalysisArtifactRepository artifacts;

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha Rao\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    /** Imports a fake repository with FILES (or, if tooLarge, a tree GitHub truncated) and returns its id. */
    private String importRepo(String auth, String name, boolean tooLarge) throws Exception {
        RemoteRepository remote = new RemoteRepository(new GitHubRepoDetails(name, new GitHubRepoDetails.Owner("asha"), null,
                "main", "Python", false, 0, 0, "https://github.com/asha/" + name), null);
        when(github.read(any(), eq("asha"), eq(name))).thenReturn(remote);
        List<GitHubTree.Entry> entries = new ArrayList<>();
        FILES.keySet().stream().sorted().forEach(p -> entries.add(new GitHubTree.Entry(p, "blob", (long) FILES.get(p).length())));
        when(github.readSnapshot(remote)).thenReturn(new RepositorySnapshot(COMMIT, new GitHubTree(tooLarge, entries)));
        String body = mvc.perform(post("/api/repositories/import").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://github.com/asha/" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return tooLarge ? JsonPath.read(mvc.perform(get("/api/repositories").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString(), "$[0].id") : JsonPath.read(body, "$.id");
    }

    /** Serves FILES at the pinned commit. */
    private void serveFiles() {
        when(sources.open(any(), eq("asha"), any(), eq("main"), anyBoolean(), eq(COMMIT))).thenAnswer(inv -> new ContextBuilderTest.FakeSnapshot() {
            {
                FILES.forEach(this::with);
            }

            @Override
            public String commitSha() {
                return COMMIT;
            }
        });
    }

    @Test
    void preparesARepositoryAndStoresNoSourceCode() throws Exception {
        String auth = register("prepare@example.com");
        mvc.perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Asha Rao","level":"UNDERGRADUATE","classYear":2,"languages":["Python"],"frameworks":[],
                 "databases":[],"experienceAreas":[],"goals":"Write cleaner, production-quality code"}"""));
        String repoId = importRepo(auth, "orders", false);
        serveFiles();

        mvc.perform(get("/api/repositories/" + repoId + "/analyses/latest").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        String body = mvc.perform(post("/api/repositories/" + repoId + "/analyses").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.profileSchemaVersion").value(1))
                .andExpect(jsonPath("$.rulesVersion").value(1))
                .andExpect(jsonPath("$.contextSchemaVersion").value(1))
                .andExpect(jsonPath("$.profile.frameworks[0].name").value("FastAPI"))
                .andExpect(jsonPath("$.profile.databases[0].name").value("PostgreSQL"))
                .andExpect(jsonPath("$.analysis.signals.length()").value(greaterThan(5)))
                .andExpect(jsonPath("$.analysis.signals[*].ruleId").value(hasItem("TESTING.TEST_FILES")))
                .andExpect(jsonPath("$.manifest.files[*].path").value(hasItem("app/main.py")))
                .andExpect(jsonPath("$.manifest.developer.level").value("UNDERGRADUATE"))
                .andExpect(jsonPath("$.manifest.developer.name").doesNotExist())
                .andExpect(jsonPath("$.stats.contextFileCount").value(greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.id");

        // No source code in the API response or in the database; only paths, reasons, sizes and hashes.
        assertThat(body).doesNotContain(SOURCE_MARKER);
        assertThat(artifacts.findAll()).allSatisfy(a -> assertThat(a.getProfileJson() + a.getSignalsJson() + a.getContextManifestJson())
                .doesNotContain(SOURCE_MARKER));

        // Same snapshot, versions and limits: the identical earlier run is returned, nothing is re-fetched.
        mvc.perform(post("/api/repositories/" + repoId + "/analyses").header("Authorization", auth))
                .andExpect(jsonPath("$.id").value(runId));
        verify(sources, times(1)).open(any(), any(), any(), any(), anyBoolean(), any());

        mvc.perform(get("/api/repositories/" + repoId + "/analyses/latest").header("Authorization", auth))
                .andExpect(jsonPath("$.id").value(runId));
        mvc.perform(get("/api/analyses/" + runId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void usersCannotSeeOrPrepareOthersRepositories() throws Exception {
        String owner = register("owner-analysis@example.com");
        String stranger = register("stranger-analysis@example.com");
        String repoId = importRepo(owner, "private-work", false);
        serveFiles();
        String runId = JsonPath.read(mvc.perform(post("/api/repositories/" + repoId + "/analyses").header("Authorization", owner))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/analyses/" + runId).header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + repoId + "/analyses/latest").header("Authorization", stranger))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/repositories/" + repoId + "/analyses").header("Authorization", stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));
    }

    @Test
    void aRateLimitMidRunFailsTheRunVisibly() throws Exception {
        String auth = register("ratelimit@example.com");
        String repoId = importRepo(auth, "busy", false);
        when(sources.open(any(), eq("asha"), eq("busy"), any(), anyBoolean(), any())).thenAnswer(inv -> new ContextBuilderTest.FakeSnapshot() {
            @Override
            public byte[] read(String path) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_RATE_LIMITED",
                        "GitHub is temporarily limiting requests. Please try again later.");
            }
        });

        mvc.perform(post("/api/repositories/" + repoId + "/analyses").header("Authorization", auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_RATE_LIMITED"));
        mvc.perform(get("/api/repositories/" + repoId + "/analyses/latest").header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("GitHub is temporarily limiting requests. Please try again later."))
                .andExpect(jsonPath("$.profile").doesNotExist());
    }

    @Test
    void onlySuccessfullyImportedRepositoriesCanBePrepared() throws Exception {
        String auth = register("notready@example.com");
        String repoId = importRepo(auth, "too-big", true);
        mvc.perform(post("/api/repositories/" + repoId + "/analyses").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_READY"));
    }
}
