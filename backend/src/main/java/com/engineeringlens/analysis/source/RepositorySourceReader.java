package com.engineeringlens.analysis.source;

import java.util.UUID;

/**
 * What analysis needs from a code host: read files of one pinned snapshot. Analysis depends on this
 * interface only; how access works (tokens, hosts) stays behind the implementation.
 */
public interface RepositorySourceReader {

    /**
     * @param commitSha the commit recorded at import, or null to pin the branch head now
     */
    SourceSnapshot open(UUID userId, String owner, String name, String branch, boolean privateRepo, String commitSha);
}
