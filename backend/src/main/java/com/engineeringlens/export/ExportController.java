package com.engineeringlens.export;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** PDF downloads. Thin: identity from the JWT; ownership and content in the services. */
@RestController
public class ExportController {

    private final ReviewPdfService reviews;
    private final LabAssessmentPdfService labs;

    public ExportController(ReviewPdfService reviews, LabAssessmentPdfService labs) {
        this.reviews = reviews;
        this.labs = labs;
    }

    @GetMapping("/api/reviews/{id}/pdf")
    ResponseEntity<byte[]> review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return download(reviews.render(userId(jwt), id));
    }

    @GetMapping("/api/scenario-labs/{id}/assessment/pdf")
    ResponseEntity<byte[]> lab(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return download(labs.render(userId(jwt), id));
    }

    private static ResponseEntity<byte[]> download(ReviewPdfService.Pdf pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store") // private content: don't keep it in shared caches
                .body(pdf.bytes());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
