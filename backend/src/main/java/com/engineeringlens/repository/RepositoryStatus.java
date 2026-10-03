package com.engineeringlens.repository;

/** Import lifecycle. The analysis phase will add its own states later; this phase only imports. */
public enum RepositoryStatus {
    IMPORTING,
    READY,
    FAILED
}
