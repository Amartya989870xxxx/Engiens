package com.engineeringlens.user;

public enum ExperienceLevel {
    SCHOOL_STUDENT(0),
    UNDERGRADUATE(4),
    GRADUATE(2),
    PROFESSIONAL(0);

    /** Number of selectable study years; 0 means year of study does not apply. */
    private final int yearsOfStudy;

    ExperienceLevel(int yearsOfStudy) {
        this.yearsOfStudy = yearsOfStudy;
    }

    public boolean hasYearOfStudy() {
        return yearsOfStudy > 0;
    }

    public int yearsOfStudy() {
        return yearsOfStudy;
    }
}
