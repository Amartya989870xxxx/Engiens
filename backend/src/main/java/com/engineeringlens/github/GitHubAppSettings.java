package com.engineeringlens.github;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub App credentials. All optional: without them the app runs with
 * public-repository support only and the "connect GitHub" option is hidden.
 */
@Component
public class GitHubAppSettings {

    private final String appId;
    private final String clientId;
    private final String clientSecret;
    private final String slug;
    private final String privateKeyPem;
    private final String frontendUrl;

    public GitHubAppSettings(
            @Value("${app.github.app.id:}") String appId,
            @Value("${app.github.app.client-id:}") String clientId,
            @Value("${app.github.app.client-secret:}") String clientSecret,
            @Value("${app.github.app.slug:}") String slug,
            @Value("${app.github.app.private-key:}") String privateKey,
            @Value("${app.github.app.private-key-path:}") String privateKeyPath,
            @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.appId = appId.trim();
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.slug = slug.trim();
        this.privateKeyPem = privateKey.isBlank() ? readKeyFile(privateKeyPath.trim()) : privateKey;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    private static String readKeyFile(String path) {
        if (path.isEmpty()) {
            return "";
        }
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            // Misconfiguration should stop startup, not surface later as a confusing GitHub error.
            throw new IllegalStateException("Cannot read GITHUB_APP_PRIVATE_KEY_PATH: " + path, e);
        }
    }

    public boolean configured() {
        return !appId.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty() && !slug.isEmpty()
                && !privateKeyPem.isBlank();
    }

    public String appId() {
        return appId;
    }

    public long appIdAsLong() {
        return Long.parseLong(appId);
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String slug() {
        return slug;
    }

    public String privateKeyPem() {
        return privateKeyPem;
    }

    public String frontendUrl() {
        return frontendUrl;
    }
}
