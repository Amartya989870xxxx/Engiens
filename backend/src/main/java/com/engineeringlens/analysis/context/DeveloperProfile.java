package com.engineeringlens.analysis.context;

import java.util.List;

/**
 * The developer's engineering profile, carried as context metadata for later interpretation. It never
 * influences profiling, signals or file selection. Name and GitHub account are deliberately left out.
 */
public record DeveloperProfile(String level, Integer classYear, String workExperience, List<String> languages,
        List<String> frameworks, List<String> databases, List<String> experienceAreas, String goals) {

    public static DeveloperProfile from(com.engineeringlens.user.UserProfile p) {
        return new DeveloperProfile(p.getLevel().name(), p.getClassYear(),
                p.getWorkExperience() == null ? null : p.getWorkExperience().name(), p.getLanguages(), p.getFrameworks(),
                p.getDatabases(), p.getExperienceAreas(), p.getGoals());
    }
}
