package com.engineeringlens.scenario;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.engineeringlens.analysis.ai.AiModelHealthRepository;
import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.AiReply;
import com.engineeringlens.analysis.ai.GeminiProvider;
import com.engineeringlens.analysis.review.ReviewFixtures;
import com.engineeringlens.analysis.source.RepositorySourceReader;
import com.engineeringlens.analysis.source.SourceSnapshot;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.github.GitHubRepoDetails;
import com.engineeringlens.github.GitHubRepositoryReader;
import com.engineeringlens.github.GitHubRepositoryReader.RemoteRepository;
import com.engineeringlens.github.GitHubRepositoryReader.RepositorySnapshot;
import com.engineeringlens.github.GitHubTree;
import com.engineeringlens.scenario.execution.ExecutionProvider;
import com.jayway.jsonpath.JsonPath;

/**
 * The world Scenario Lab tests run in: real HTTP, security, services and database; GitHub faked; the real
 * Gemini provider spied on so no request leaves the machine. Subclasses can change what the "model" answers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ScenarioFlowSupport {

    protected static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    protected static final Map<String, String> FILES = Map.of(
            "requirements.txt", "fastapi\npsycopg\npytest\n",
            "app/main.py", "from fastapi import FastAPI\napp = FastAPI()\n",
            "app/services/orders.py", "def place(order, store):\n    store.append(order)\n    return order\n",
            "tests/test_orders.py", "def test_place():\n    assert True\n",
            "README.md", "# Orders\n");

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected GitHubRepositoryReader github;

    @MockitoBean
    protected RepositorySourceReader sources;

    @MockitoSpyBean
    protected GeminiProvider gemini;

    /** The code sandbox, faked: see {@link ScenarioFixtures#judge}. Real containers are tested in SandboxExecutionTest. */
    @MockitoBean
    protected ExecutionProvider sandbox;

    @Autowired
    protected AiModelHealthRepository health;

    /** Every prompt the "model" received. */
    protected final List<AiPrompt> prompts = java.util.Collections.synchronizedList(new ArrayList<>());

    /** What the "model" answers; by default valid reviews, scenario plans and scenarios. */
    protected Function<AiPrompt, String> answers = ScenarioFlowSupport::defaultAnswer;

    @BeforeEach
    void fakeTheModel() {
        health.deleteAll(); // startup marked Gemini DISABLED (no key in tests); earlier tests may have cooled models down
        doReturn(true).when(gemini).configured();
        doAnswer(inv -> {
            AiPrompt prompt = inv.getArgument(1);
            prompts.add(prompt);
            return new AiReply(answers.apply(prompt), 1000, 1000);
        }).when(gemini).generate(anyString(), any(), any());
        when(sandbox.available()).thenReturn(true);
        when(sandbox.execute(any())).thenAnswer(inv -> ScenarioFixtures.judge(inv.getArgument(0)));
    }

    protected static String defaultAnswer(AiPrompt prompt) {
        if (ScenarioFixtures.isPlan(prompt)) {
            return ScenarioFixtures.plan(prompt);
        }
        if (ScenarioFixtures.isBuild(prompt)) {
            return ScenarioFixtures.scenario(prompt, ScenarioFixtures.STARTER);
        }
        if (ScenarioFixtures.isAssess(prompt)) {
            return ScenarioFixtures.evaluation();
        }
        if (ScenarioFixtures.isSummary(prompt)) {
            return ScenarioFixtures.summary();
        }
        if (ScenarioFixtures.isLabTeaching(prompt)) {
            return ScenarioFixtures.labTeaching(prompt);
        }
        return reviewAnswer(prompt);
    }

    protected static String reviewAnswer(AiPrompt prompt) {
        return prompt.system().contains("Write advice for THIS developer") ? ReviewFixtures.teachingJson() : ReviewFixtures.validJson();
    }

    protected String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha Rao\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        String auth = "Bearer " + JsonPath.read(body, "$.token");
        mvc.perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Asha Rao","level":"UNDERGRADUATE","classYear":2,"languages":["Python"],"frameworks":[],
                 "databases":[],"experienceAreas":[],"goals":"Write cleaner, production-quality code"}"""));
        return auth;
    }

    /** Makes "github.com/{owner}/{name}" importable and serves its files at the pinned commit. */
    protected void fakeGitHubRepository(String owner, String name) {
        RemoteRepository remote = new RemoteRepository(new GitHubRepoDetails(name, new GitHubRepoDetails.Owner(owner), null,
                "main", "Python", false, 0, 0, "https://github.com/" + owner + "/" + name), null);
        when(github.read(any(), eq(owner), eq(name))).thenReturn(remote);
        List<GitHubTree.Entry> entries = new ArrayList<>();
        FILES.keySet().stream().sorted().forEach(p -> entries.add(new GitHubTree.Entry(p, "blob", (long) FILES.get(p).length())));
        when(github.readSnapshot(remote)).thenReturn(new RepositorySnapshot(COMMIT, new GitHubTree(false, entries)));
        when(sources.open(any(), eq(owner), any(), eq("main"), anyBoolean(), eq(COMMIT))).thenAnswer(inv -> snapshot(COMMIT));
    }

    /** New commits were pushed: GitHub now reports {@code commit} as the branch head, and files are read at it. */
    protected void moveBranch(String owner, String name, String commit) {
        RemoteRepository remote = github.read(null, owner, name);
        List<GitHubTree.Entry> entries = new ArrayList<>();
        FILES.keySet().stream().sorted().forEach(p -> entries.add(new GitHubTree.Entry(p, "blob", (long) FILES.get(p).length())));
        when(github.readSnapshot(remote)).thenReturn(new RepositorySnapshot(commit, new GitHubTree(false, entries)));
        when(sources.open(any(), eq(owner), any(), eq("main"), anyBoolean(), eq(commit))).thenAnswer(inv -> snapshot(commit));
    }

    protected String importRepo(String auth, String owner, String name) throws Exception {
        fakeGitHubRepository(owner, name);
        String body = mvc.perform(post("/api/repositories/import").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://github.com/" + owner + "/" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** A completed review (reviews run inline in tests). Returns the review id, i.e. the id in /reviews/:id. */
    protected String review(String auth, String repoId) throws Exception {
        String body = mvc.perform(post("/api/repositories/" + repoId + "/reviews").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Starts a lab and returns its id (generation runs inline unless a test enables async). */
    protected String startLab(String auth, String source, int count) throws Exception {
        String body = mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{" + source + ",\"roles\":[\"BACKEND_ENGINEER\"],\"seniority\":\"SDE2\",\"scenarioCount\":" + count + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static SourceSnapshot snapshot(String commit) {
        return new SourceSnapshot() {
            @Override
            public String commitSha() {
                return commit;
            }

            @Override
            public boolean pinnedAtImport() {
                return true;
            }

            @Override
            public byte[] read(String path) {
                String text = FILES.get(path);
                if (text == null) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_FILE_NOT_FOUND", "File not found at the imported commit");
                }
                return text.getBytes(StandardCharsets.UTF_8);
            }
        };
    }
}
