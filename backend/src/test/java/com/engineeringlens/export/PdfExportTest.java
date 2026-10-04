package com.engineeringlens.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;

import com.engineeringlens.scenario.ScenarioFixtures;
import com.engineeringlens.scenario.ScenarioFlowSupport;
import com.jayway.jsonpath.JsonPath;

/** PDFs come from persisted data only, belong to their owner, and contain what the pages contain. */
class PdfExportTest extends ScenarioFlowSupport {

    private static String text(byte[] pdf) throws Exception {
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder s = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                s.append(extractor.getTextFromPage(i)).append('\n');
            }
            return s.toString();
        }
    }

    private byte[] download(String auth, String url) throws Exception {
        byte[] pdf = mvc.perform(get(url).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"engiens-")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        String dir = System.getProperty("pdf.out"); // -Dpdf.out=/some/dir keeps the files for a visual check
        if (dir != null) {
            java.nio.file.Files.write(java.nio.file.Path.of(dir, url.replaceAll("[^a-z]+", "-") + System.nanoTime() + ".pdf"), pdf);
        }
        return pdf;
    }

    @Test
    void theOwnerCanDownloadTheReviewAndTheLabAssessmentAsPdfsAndNobodyElseCan() throws Exception {
        String auth = register("pdf-owner@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String reviewId = review(auth, repoId);

        // A review PDF works before any lab exists (empty optional sections don't break it).
        byte[] early = download(auth, "/api/reviews/" + reviewId + "/pdf");
        assertThat(new String(early, 0, 5)).isEqualTo("%PDF-");
        String earlyText = text(early);
        assertThat(earlyText).contains("Engineering Review", "orders", "Overall assessment", "Engineering dimensions",
                "Review limitations", "No Scenario Lab attempts for this repository yet.");

        // Complete a lab, then both documents reflect it.
        String body = mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewId\":\"" + reviewId + "\",\"roles\":[\"BACKEND_ENGINEER\"],\"seniority\":\"SDE2\",\"scenarioCount\":5}"))
                .andReturn().getResponse().getContentAsString();
        String labId = JsonPath.read(body, "$.id");
        List<String> scenarios = JsonPath.read(body, "$.scenarios[*].id");
        for (int i = 0; i < scenarios.size(); i++) {
            String files = "[{\"path\":\"orders.py\",\"content\":" + ScenarioFixtures.JSON.writeValueAsString(ScenarioFixtures.FIXED) + "}]";
            mvc.perform(post("/api/scenario-labs/" + labId + "/scenarios/" + scenarios.get(i) + "/submit").header("Authorization", auth)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CODE\",\"files\":" + files + "}")).andExpect(status().isOk());
        }
        int aiCallsBefore = prompts.size();

        String lab = text(download(auth, "/api/scenario-labs/" + labId + "/assessment/pdf"));
        assertThat(lab).contains("Scenario Lab Assessment", "orders", "Backend Engineer", "SDE2", "Overall assessment", "Scenario 1",
                "Scenario 5", "Your submission", "Objective results", "3 OF 3 CHECKS PASSED", "What you got right", "Learning recommendations",
                "Idempotency");
        assertThat(lab).doesNotContain("HIDDEN_CHECK_SOURCE");
        assertThat(text(download(auth, "/api/reviews/" + reviewId + "/pdf"))).contains("Appendix: Scenario Lab history", "LAB #1");
        assertThat(prompts).hasSize(aiCallsBefore); // exporting never calls the AI

        String mallory = register("pdf-intruder@example.com");
        mvc.perform(get("/api/reviews/" + reviewId + "/pdf").header("Authorization", mallory))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mvc.perform(get("/api/scenario-labs/" + labId + "/assessment/pdf").header("Authorization", mallory))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/" + reviewId + "/pdf")).andExpect(status().isUnauthorized());
    }

    /** Found in the browser: without this header the UI couldn't read the file name across origins. */
    @Test
    void theFrontendCanReadTheFileNameAcrossOrigins() throws Exception {
        String auth = register("pdf-cors@example.com");
        String reviewId = review(auth, importRepo(auth, "asha", "orders"));
        mvc.perform(get("/api/reviews/" + reviewId + "/pdf").header("Authorization", auth).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Content-Disposition")));
    }

    @Test
    void anOpenLabHasNoAssessmentToExport() throws Exception {
        String auth = register("pdf-open@example.com");
        String repoId = importRepo(auth, "asha", "orders");
        String body = mvc.perform(post("/api/scenario-labs").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repositoryId\":\"" + repoId + "\",\"roles\":[\"BACKEND_ENGINEER\"],\"seniority\":\"SDE1\",\"scenarioCount\":5}"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/scenario-labs/" + JsonPath.read(body, "$.id") + "/assessment/pdf").header("Authorization", auth))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_ASSESSMENT_NOT_READY"));
    }
}
