package com.engineeringlens.github;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.engineeringlens.common.ApiException;

/** Shared HTTP setup and error shapes for every GitHub call. */
final class GitHubHttp {

    static final String API_URL = "https://api.github.com";

    private GitHubHttp() {
    }

    static RestClient.Builder builder(String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeouts)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json");
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "Could not reach GitHub. Please try again.");
    }

    static ApiException rateLimited() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_RATE_LIMITED",
                "GitHub is limiting requests right now. Please try again in a few minutes.");
    }
}
