package com.engineeringlens.user;

import java.util.List;

public record ProfileResponse(
        String name,
        ExperienceLevel level,
        Integer classYear,
        WorkExperience workExperience,
        List<String> languages,
        List<String> frameworks,
        List<String> databases,
        List<String> experienceAreas,
        String githubUsername,
        String goals) {

    static ProfileResponse from(User user, UserProfile p) {
        return new ProfileResponse(user.getName(), p.getLevel(), p.getClassYear(), p.getWorkExperience(),
                p.getLanguages(), p.getFrameworks(), p.getDatabases(), p.getExperienceAreas(),
                p.getGithubUsername(), p.getGoals());
    }
}
