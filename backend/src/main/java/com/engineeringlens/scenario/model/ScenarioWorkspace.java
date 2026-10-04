package com.engineeringlens.scenario.model;

import java.util.List;

import com.engineeringlens.scenario.ScenarioLanguage;

/**
 * The starter code: a small, self-contained extract adapted from the repository. Read-only files (for
 * example an in-memory stand-in for the database) always run as generated, whatever the user sends.
 */
public record ScenarioWorkspace(ScenarioLanguage language, List<File> files) {

    public record File(String path, String content, boolean editable) {
    }
}
