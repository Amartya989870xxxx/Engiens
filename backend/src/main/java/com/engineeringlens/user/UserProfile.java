package com.engineeringlens.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExperienceLevel level;

    @Column(name = "class_year")
    private Integer classYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_experience")
    private WorkExperience workExperience;

    @Convert(converter = StringListConverter.class)
    @Column(nullable = false)
    private List<String> languages;

    @Convert(converter = StringListConverter.class)
    @Column(nullable = false)
    private List<String> frameworks;

    @Convert(converter = StringListConverter.class)
    @Column(nullable = false)
    private List<String> databases;

    @Convert(converter = StringListConverter.class)
    @Column(name = "experience_areas", nullable = false)
    private List<String> experienceAreas;

    @Column(name = "github_username")
    private String githubUsername;

    private String goals;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {
    }

    public UserProfile(UUID userId) {
        this.userId = userId;
    }

    public void update(ExperienceLevel level, Integer classYear, WorkExperience workExperience, List<String> languages,
            List<String> frameworks, List<String> databases, List<String> experienceAreas,
            String githubUsername, String goals) {
        this.level = level;
        this.classYear = classYear;
        this.workExperience = workExperience;
        this.languages = languages;
        this.frameworks = frameworks;
        this.databases = databases;
        this.experienceAreas = experienceAreas;
        this.githubUsername = githubUsername;
        this.goals = goals;
        this.updatedAt = Instant.now();
    }

    public ExperienceLevel getLevel() {
        return level;
    }

    public Integer getClassYear() {
        return classYear;
    }

    public WorkExperience getWorkExperience() {
        return workExperience;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public List<String> getFrameworks() {
        return frameworks;
    }

    public List<String> getDatabases() {
        return databases;
    }

    public List<String> getExperienceAreas() {
        return experienceAreas;
    }

    public String getGithubUsername() {
        return githubUsername;
    }

    public String getGoals() {
        return goals;
    }
}
