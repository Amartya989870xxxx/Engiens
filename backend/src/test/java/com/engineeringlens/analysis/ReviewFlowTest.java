package com.engineeringlens.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.engineeringlens.analysis.ai.AiFailureType;
import com.engineeringlens.analysis.ai.AiModelHealthRepository;
import com.engineeringlens.analysis.ai.AiModelState;
import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.AiProviderException;
import com.engineeringlens.analysis.ai.AiReply;
import com.engineeringlens.analysis.ai.GeminiProvider;
import com.engineeringlens.analysis.review.ReviewFixtures;
import com.engineeringlens.analysis.review.ReviewRunRepository;
import com.engineeringlens.analysis.review.StoredReviewRepository;
import com.engineeringlens.analysis.source.RepositorySourceReader;
import com.engineeringlens.github.GitHubRepoDetails;
import com.engineeringlens.github.GitHubRepositoryReader;
import com.engineeringlens.github.GitHubRepositoryReader.RemoteRepository;
import com.engineeringlens.github.GitHubRepositoryReader.RepositorySnapshot;
import com.engineeringlens.github.GitHubTree;
import com.jayway.jsonpath.JsonPath;

/**
 * Start a review → background run → persisted review → read back, through HTTP and the real database.
 * GitHub is faked, and the real Gemini provider is spied on so no request leaves the machine; the router,
 * validator, health tracking and persistence are all real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewFlowTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String SOURCE_MARKER = "UNIQUE_REVIEW_SOURCE_MARKER_7";
    private static final String FIRST_MODEL = "gemini-3.8-flash";

    private static final Map<String, String> FILES = Map.of(
            "requirements.txt", "fastapi\npsycopg\npytest\n",
            "app/main.py", "from fastapi import FastAPI\napp = FastAPI()  # " + SOURCE_MARKER + "\n",
            "app/services/orders.py", "def place(order):\n    return order\n",
            "tests/test_orders.py", "def test_place():\n    assert True\n",
            "README.md", "# Orders\n");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GitHubRepositoryReader github;

    @MockitoBean
    RepositorySourceReader sources;

    @MockitoSpyBean
    GeminiProvider gemini;

    @Autowired
    ReviewRunRepository reviewRuns;

    @Autowired
    StoredReviewRepository storedReviews;

    @Autowired
    AiModelHealthRepository health;

    /** Every prompt the "model" received, to prove what was (and wasn't) sent. */
    private final List<AiPrompt> prompts = new ArrayList<>();

    @BeforeEach
    void answerWithAValidReview() {
        // Startup marked Gemini DISABLED (no key in tests) and earlier tests may have cooled models down.
        health.deleteAll();
        doReturn(true).when(gemini).configured();
        doAnswer(inv -> {
            AiPrompt prompt = inv.getArgument(1);
            prompts.add(prompt);
            return isTeaching(prompt) ? new AiReply(ReviewFixtures.teachingJson(), 300, 400) : new AiReply(ReviewFixtures.validJson(), 1000, 2000);
        }).when(gemini).generate(anyString(), any(), any());
    }

    /** The second AI step (advice for this developer) is recognisable by its task. */
    private static boolean isTeaching(AiPrompt prompt) {
        return prompt.system().contains("Write advice for THIS developer");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha Rao\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        String auth = "Bearer " + JsonPath.read(body, "$.token");
        mvc.perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Asha Rao","level":"UNDERGRADUATE","classYear":2,"languages":["Python"],"frameworks":[],
                 "databases":[],"experienceAreas":[],"goals":"Write cleaner, production-quality code"}"""));
        return auth;
    }

    /** Imports a fake repository and serves its files at the pinned commit. */
    private String importRepo(String auth, String name) throws Exception {
        RemoteRepository remote = new RemoteRepository(new GitHubRepoDetails(name, new GitHubRepoDetails.Owner("asha"), null,
                "main", "Python", false, 0, 0, "https://github.com/asha/" + name), null);
        when(github.read(any(), eq("asha"), eq(name))).thenReturn(remote);
        List<GitHubTree.Entry> entries = new ArrayList<>();
        FILES.keySet().stream().sorted().forEach(p -> entries.add(new GitHubTree.Entry(p, "blob", (long) FILES.get(p).length())));
        when(github.readSnapshot(remote)).thenReturn(new RepositorySnapshot(COMMIT, new GitHubTree(false, entries)));
        when(sources.open(any(), eq("asha"), any(), eq("main"), anyBoolean(), eq(COMMIT))).thenAnswer(inv -> new ContextBuilderTest.FakeSnapshot() {
            {
                FILES.forEach(this::with);
            }

            @Override
            public String commitSha() {
                return COMMIT;
            }
        });
        String body = mvc.perform(post("/api/repositories/import").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://github.com/asha/" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String startReview(String auth, String repoId, boolean regenerate) throws Exception {
        String body = mvc.perform(post("/api/repositories/" + repoId + "/reviews" + (regenerate ? "?regenerate=true" : ""))
                .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void reviewsARepositoryAndStoresTheValidatedReviewButNoSource() throws Exception {
        String auth = register("review-flow@example.com");
        String repoId = importRepo(auth, "orders");

        mvc.perform(get("/api/repositories/" + repoId + "/reviews/latest").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        String reviewId = startReview(auth, repoId, false);

        String body = mvc.perform(get("/api/reviews/" + reviewId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.repositoryName").value("orders"))
                .andExpect(jsonPath("$.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.provider").value("gemini"))
                .andExpect(jsonPath("$.model").value(FIRST_MODEL))
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.review.dimensions.length()").value(16))
                .andExpect(jsonPath("$.review.reviewMetadata.reviewRunId").value(reviewId))
                .andExpect(jsonPath("$.review.reviewMetadata.commitSha").value(COMMIT))
                .andExpect(jsonPath("$.review.personalization.audience").value("FOUNDATION"))
                .andExpect(jsonPath("$.review.personalizedLearningPlan.nextThingsToLearn[0].topic").value("Writing your first integration test"))
                .andReturn().getResponse().getContentAsString();

        // Two AI steps: the assessment saw the source but not who wrote it; the teaching saw the developer but no source.
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0).system() + prompts.get(0).user()).contains(SOURCE_MARKER).doesNotContain("UNDERGRADUATE");
        assertThat(prompts.get(1).user()).contains("UNDERGRADUATE").doesNotContain(SOURCE_MARKER);
        // ...but no source is returned or stored, and the prompt itself is never persisted.
        assertThat(body).doesNotContain(SOURCE_MARKER);
        assertThat(storedReviews.findAll()).allSatisfy(r -> assertThat(r.getReviewJson()).doesNotContain(SOURCE_MARKER));
        assertThat(reviewRuns.findAll()).allSatisfy(r -> assertThat(String.valueOf(r.getErrorMessage())).doesNotContain(SOURCE_MARKER));

        // Token usage and the model's health are recorded.
        var run = reviewRuns.findById(java.util.UUID.fromString(reviewId)).orElseThrow();
        assertThat(run.getInputTokens()).isEqualTo(1300); // both steps
        assertThat(run.getAttemptCount()).isEqualTo(2);
        var modelHealth = health.findById("gemini:" + FIRST_MODEL).orElseThrow();
        assertThat(modelHealth.getState()).isEqualTo(AiModelState.HEALTHY);
        assertThat(modelHealth.getLastSuccessAt()).isNotNull();

        // Latest, per-repository history and the sidebar's recent list all find it.
        mvc.perform(get("/api/repositories/" + repoId + "/reviews/latest").header("Authorization", auth))
                .andExpect(jsonPath("$.id").value(reviewId));
        mvc.perform(get("/api/repositories/" + repoId + "/reviews").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].review").doesNotExist());
        mvc.perform(get("/api/reviews").header("Authorization", auth))
                .andExpect(jsonPath("$[0].id").value(reviewId));
    }

    @Test
    void reusesACompletedReviewUnlessARegenerationIsRequested() throws Exception {
        String auth = register("review-reuse@example.com");
        String repoId = importRepo(auth, "reuse");

        String first = startReview(auth, repoId, false);
        assertThat(startReview(auth, repoId, false)).isEqualTo(first);
        assertThat(prompts).hasSize(2); // the reused review cost no AI call

        String second = startReview(auth, repoId, true);
        assertThat(second).isNotEqualTo(first);
        assertThat(prompts).hasSize(4);
        mvc.perform(get("/api/repositories/" + repoId + "/reviews/latest").header("Authorization", auth))
                .andExpect(jsonPath("$.id").value(second));
        mvc.perform(get("/api/repositories/" + repoId + "/reviews").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void fallsBackToTheNextModelWhenTheFirstIsRateLimited() throws Exception {
        String auth = register("review-fallback@example.com");
        String repoId = importRepo(auth, "fallback");
        AtomicReference<String> served = new AtomicReference<>();
        doAnswer(inv -> {
            String model = inv.getArgument(0);
            if (model.equals(FIRST_MODEL)) {
                // Longer than the router will wait: cool the model down instead of sleeping in a test.
                throw new AiProviderException(AiFailureType.RATE_LIMITED, "quota", java.time.Duration.ofMinutes(5));
            }
            AiPrompt prompt = inv.getArgument(1);
            if (isTeaching(prompt)) {
                return new AiReply(ReviewFixtures.teachingJson(), 10, 20);
            }
            served.set(model);
            return new AiReply(ReviewFixtures.validJson(), 10, 20);
        }).when(gemini).generate(anyString(), any(), any());

        String reviewId = startReview(auth, repoId, false);

        mvc.perform(get("/api/reviews/" + reviewId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.model").value(served.get()))
                .andExpect(jsonPath("$.fallbackUsed").value(true));
        assertThat(served.get()).isEqualTo("gemini-3.7-flash");
        assertThat(health.findById("gemini:" + FIRST_MODEL).orElseThrow().getState()).isEqualTo(AiModelState.COOLDOWN);
    }

    @Test
    void aReviewStillCompletesWhenPersonalisationFails() throws Exception {
        String auth = register("review-teaching-fails@example.com");
        String repoId = importRepo(auth, "untaught");
        doAnswer(inv -> {
            AiPrompt prompt = inv.getArgument(1);
            if (isTeaching(prompt)) {
                throw new AiProviderException(AiFailureType.REQUEST_INVALID, "rejected");
            }
            return new AiReply(ReviewFixtures.validJson(), 10, 20);
        }).when(gemini).generate(anyString(), any(), any());

        String reviewId = startReview(auth, repoId, false);

        mvc.perform(get("/api/reviews/" + reviewId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.review.personalization").doesNotExist())
                .andExpect(jsonPath("$.review.reviewLimitations[-1]").value(org.hamcrest.Matchers.startsWith("Personalised advice couldn't")))
                .andExpect(jsonPath("$.review.dimensions[0].assessment").value(
                        ReviewFixtures.valid().get("dimensions").get(0).get("assessment").asString()));
    }

    @Test
    void aFailedReviewIsRecordedWithAUserSafeMessage() throws Exception {
        String auth = register("review-failure@example.com");
        String repoId = importRepo(auth, "failing");
        doThrow(new AiProviderException(AiFailureType.REQUEST_INVALID, "bad request"))
                .when(gemini).generate(anyString(), any(), any());

        String reviewId = startReview(auth, repoId, false);

        mvc.perform(get("/api/reviews/" + reviewId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("AI_REQUEST_REJECTED"))
                .andExpect(jsonPath("$.errorMessage").value("The review request couldn't be processed. Please try again later."))
                .andExpect(jsonPath("$.review").doesNotExist());
        // A failure is never offered as "the latest review".
        mvc.perform(get("/api/repositories/" + repoId + "/reviews/latest").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void excerptsShowOnlyReviewedLines() throws Exception {
        String auth = register("review-excerpt@example.com");
        String repoId = importRepo(auth, "excerpt");
        String reviewId = startReview(auth, repoId, false);

        mvc.perform(get("/api/reviews/" + reviewId + "/excerpt").header("Authorization", auth)
                .param("file", "app/main.py").param("lineStart", "1").param("lineEnd", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].number").value(1))
                .andExpect(jsonPath("$.lines[0].text").value("from fastapi import FastAPI"));

        mvc.perform(get("/api/reviews/" + reviewId + "/excerpt").header("Authorization", auth)
                .param("file", "app/main.py").param("lineStart", "50").param("lineEnd", "60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCERPT_OUT_OF_RANGE"));
        mvc.perform(get("/api/reviews/" + reviewId + "/excerpt").header("Authorization", auth)
                .param("file", "secrets/not-reviewed.py").param("lineStart", "1").param("lineEnd", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewsAreVisibleOnlyToTheirOwner() throws Exception {
        String owner = register("review-owner@example.com");
        String stranger = register("review-stranger@example.com");
        String repoId = importRepo(owner, "private-orders");
        String reviewId = startReview(owner, repoId, false);

        mvc.perform(get("/api/reviews/" + reviewId).header("Authorization", stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mvc.perform(get("/api/reviews/" + reviewId + "/excerpt").header("Authorization", stranger)
                .param("file", "app/main.py").param("lineStart", "1").param("lineEnd", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/repositories/" + repoId + "/reviews").header("Authorization", stranger))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + repoId + "/reviews").header("Authorization", stranger))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews").header("Authorization", stranger))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/reviews/" + reviewId))
                .andExpect(status().isUnauthorized());
    }
}
