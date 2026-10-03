package com.engineeringlens.user;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.github.GitHubProfileUrl;

@Service
public class ProfileService {

    private final UserRepository users;
    private final UserProfileRepository profiles;

    public ProfileService(UserRepository users, UserProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        User user = findUser(userId);
        UserProfile profile = profiles.findById(userId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Profile has not been created yet"));
        return ProfileResponse.from(user, profile);
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID userId) {
        return profiles.existsById(userId);
    }

    @Transactional
    public ProfileResponse save(UUID userId, ProfileRequest req) {
        User user = findUser(userId);
        UserProfile profile = profiles.findById(userId).orElseGet(() -> new UserProfile(userId));
        Integer classYear = validClassYear(req.level(), req.classYear());
        WorkExperience workExperience = validWorkExperience(req.level(), req.workExperience());
        String githubUrl = blankToNull(req.githubUrl());
        String githubUsername = githubUrl == null ? null : GitHubProfileUrl.parseUsername(githubUrl);
        profile.update(req.level(), classYear, workExperience, clean(req.languages()), clean(req.frameworks()),
                clean(req.databases()), clean(req.experienceAreas()), githubUsername, blankToNull(req.goals()));
        user.setName(req.name().trim());
        profiles.save(profile);
        return ProfileResponse.from(user, profile);
    }

    /** Year of study is required for university levels and ignored for everyone else. */
    static Integer validClassYear(ExperienceLevel level, Integer classYear) {
        if (!level.hasYearOfStudy()) {
            return null;
        }
        if (classYear == null || classYear < 1 || classYear > level.yearsOfStudy()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLASS_YEAR",
                    "Choose a year of study between 1 and " + level.yearsOfStudy());
        }
        return classYear;
    }

    /** Years of experience is required for working professionals and ignored for students. */
    static WorkExperience validWorkExperience(ExperienceLevel level, WorkExperience workExperience) {
        if (level != ExperienceLevel.PROFESSIONAL) {
            return null;
        }
        if (workExperience == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WORK_EXPERIENCE",
                    "Choose your years of experience");
        }
        return workExperience;
    }

    private User findUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User no longer exists"));
    }

    /** Trims, drops commas (our storage separator) and removes duplicates. */
    private static List<String> clean(List<String> values) {
        return values.stream().map(v -> v.replace(",", " ").trim()).filter(v -> !v.isEmpty()).distinct().toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
