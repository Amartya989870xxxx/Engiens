package com.engineeringlens.scenario.execution;

/** One file of a scenario workspace: a relative path and its text. */
public record WorkspaceFile(String path, String content) {
}
