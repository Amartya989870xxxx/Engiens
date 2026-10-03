package com.engineeringlens.github;

/**
 * @param available whether private-repository access is offered at all (GitHub App configured)
 * @param manageUrl GitHub page where the user changes which repositories are shared
 */
public record GitHubConnectionStatus(boolean available, boolean connected, String login, String manageUrl) {
}
