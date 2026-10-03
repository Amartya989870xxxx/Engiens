package com.engineeringlens.github;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.engineeringlens.github.GitHubAppClient.Account;
import com.engineeringlens.github.GitHubAppClient.Installation;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.github.app.id=42", "app.github.app.client-id=Iv1.client", "app.github.app.client-secret=secret",
        "app.github.app.slug=lens-test", "app.github.app.private-key=unused-because-client-is-mocked" })
class GitHubConnectionFlowTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GitHubAppClient github;

    @MockitoBean
    GitHubClient publicGithub;

    private static final GitHubRepo REPO = new GitHubRepo("lens", null, "Java", 3, false, true,
            "https://github.com/asha/lens", null);

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private String startConnect(String auth) throws Exception {
        String body = mvc.perform(post("/api/github/connection").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(startsWith("https://github.com/apps/lens-test/installations/new?state=")))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.url");
        return url.substring(url.indexOf("state=") + 6);
    }

    @Test
    void connectsVerifiedInstallationAndDisconnects() throws Exception {
        String auth = register("connect@example.com");
        mvc.perform(get("/api/github/connection").header("Authorization", auth))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.connected").value(false));

        String state = startConnect(auth);
        when(github.exchangeCode("code-1")).thenReturn("ghu_1");
        when(github.userLogin("ghu_1")).thenReturn("asha");
        when(github.userInstallations("ghu_1")).thenReturn(List.of(new Installation(7, 42, new Account("asha", "User"))));

        // GitHub's redirect carries no bearer token; the state value identifies the user.
        mvc.perform(get("/api/github/callback").param("state", state).param("code", "code-1").param("installation_id", "7"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/onboarding?github=connected"));

        mvc.perform(get("/api/github/connection").header("Authorization", auth))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.login").value("asha"))
                .andExpect(jsonPath("$.manageUrl").value("https://github.com/settings/installations/7"));

        // A state value works once only.
        mvc.perform(get("/api/github/callback").param("state", state).param("code", "code-1"))
                .andExpect(header().string("Location", endsWith("github=GITHUB_STATE_INVALID")));

        mvc.perform(delete("/api/github/connection").header("Authorization", auth)).andExpect(status().isNoContent());
        mvc.perform(get("/api/github/connection").header("Authorization", auth))
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void rejectsAnInstallationTheGitHubUserCannotAccess() throws Exception {
        String auth = register("tamper@example.com");
        String state = startConnect(auth);
        when(github.exchangeCode("code-2")).thenReturn("ghu_2");
        when(github.userLogin("ghu_2")).thenReturn("mallory");
        when(github.userInstallations("ghu_2")).thenReturn(List.of(new Installation(9, 42, new Account("mallory", "User"))));

        mvc.perform(get("/api/github/callback").param("state", state).param("code", "code-2").param("installation_id", "7"))
                .andExpect(header().string("Location", endsWith("github=GITHUB_INSTALLATION_NOT_YOURS")));
        mvc.perform(get("/api/github/connection").header("Authorization", auth))
                .andExpect(jsonPath("$.connected").value(false));
    }

    /** The frontend reads these exact field names; GitHub's snake_case must not leak through. */
    @Test
    void repositoryResponsesUseOurOwnFieldNames() throws Exception {
        String auth = register("contract@example.com");
        when(publicGithub.listPublicRepos("asha")).thenReturn(List.of(REPO));
        mvc.perform(get("/api/github/projects").param("profileUrl", "https://github.com/asha").header("Authorization", auth))
                .andExpect(jsonPath("$.projects[0].htmlUrl").value("https://github.com/asha/lens"))
                .andExpect(jsonPath("$.projects[0].stars").value(3))
                .andExpect(jsonPath("$.projects[0].privateRepo").value(true))
                .andExpect(jsonPath("$.projects[0].html_url").doesNotExist())
                .andExpect(jsonPath("$.projects[0].stargazers_count").doesNotExist());
    }

    @Test
    void unknownStateIsRejected() throws Exception {
        mvc.perform(get("/api/github/callback").param("state", "made-up").param("code", "x"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", endsWith("github=GITHUB_STATE_INVALID")));
    }
}
