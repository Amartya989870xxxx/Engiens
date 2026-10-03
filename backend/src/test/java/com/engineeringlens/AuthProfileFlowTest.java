package com.engineeringlens;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthProfileFlowTest {

    @Autowired
    MockMvc mvc;

    private static final String PROFILE = """
            {"name":"Asha Rao","level":"UNDERGRADUATE","classYear":2,
             "languages":["Python","Java"],"frameworks":["FastAPI"],"databases":["PostgreSQL"],
             "experienceAreas":["Backend"],"githubUrl":"https://github.com/asha-rao","goals":"Learn scaling"}""";

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.profileCompleted").value(false))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    @Test
    void registerLoginProfileFlow() throws Exception {
        String token = register("flow@example.com");

        String loginBody = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"FLOW@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String loginToken = JsonPath.read(loginBody, "$.token");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow@example.com"));

        mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languages[1]").value("Java"))
                .andExpect(jsonPath("$.githubUsername").value("asha-rao"));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.profileCompleted").value(true));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        register("dup@example.com");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"email\":\"dup@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void wrongPasswordIsRejectedWithGenericError() throws Exception {
        register("wrong@example.com");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"wrong@example.com\",\"password\":\"nope-nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void protectedEndpointsRequireToken() throws Exception {
        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/profile").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileRequiresAtLeastOneLanguage() throws Exception {
        String token = register("lang@example.com");
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("[\"Python\",\"Java\"]", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.languages").exists());
    }

    @Test
    void yearOfStudyMustFitAcademicStatus() throws Exception {
        String token = register("year@example.com");
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("\"level\":\"UNDERGRADUATE\",\"classYear\":2", "\"level\":\"GRADUATE\",\"classYear\":3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLASS_YEAR"));
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("\"classYear\":2", "\"classYear\":null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLASS_YEAR"));
    }

    @Test
    void professionalsGiveYearsOfExperienceInsteadOfYearOfStudy() throws Exception {
        String token = register("pro@example.com");
        String professional = PROFILE.replace("\"UNDERGRADUATE\"", "\"PROFESSIONAL\"");
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(professional))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_EXPERIENCE"));
        // A stray year of study is dropped rather than stored.
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(professional.replace("\"classYear\":2,", "\"classYear\":2,\"workExperience\":\"THREE_TO_FIVE_YEARS\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classYear").doesNotExist())
                .andExpect(jsonPath("$.workExperience").value("THREE_TO_FIVE_YEARS"));
        // Students never carry years of experience.
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("\"classYear\":2,", "\"classYear\":2,\"workExperience\":\"OVER_TEN_YEARS\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workExperience").doesNotExist());
    }

    @Test
    void repositoryLinkIsRejectedAsGitHubProfile() throws Exception {
        String token = register("gh@example.com");
        mvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("github.com/asha-rao", "github.com/asha-rao/todo-api")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GITHUB_URL"));
    }
}
