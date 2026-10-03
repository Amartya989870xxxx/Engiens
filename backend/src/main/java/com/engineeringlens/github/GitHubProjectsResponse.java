package com.engineeringlens.github;

import java.util.List;

public record GitHubProjectsResponse(String username, List<RepositorySummary> projects) {
}
