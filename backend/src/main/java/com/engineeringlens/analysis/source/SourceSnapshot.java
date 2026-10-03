package com.engineeringlens.analysis.source;

/** Read access to one exact commit of a repository, for the duration of one analysis run. */
public interface SourceSnapshot {

    String commitSha();

    /** True when the commit was recorded at import (the inventory describes exactly this snapshot). */
    boolean pinnedAtImport();

    /** Raw bytes of one file. Throws ApiException (e.g. SOURCE_FILE_NOT_FOUND, GITHUB_RATE_LIMITED) on failure. */
    byte[] read(String path);
}
