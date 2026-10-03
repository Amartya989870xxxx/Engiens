package com.engineeringlens.analysis.source;

import java.util.HashMap;
import java.util.Map;

import com.engineeringlens.common.ApiException;

/**
 * Fetches each file at most once per analysis run and decides what is recoverable. A missing or
 * non-text file is skipped with a recorded reason; anything else (rate limit, no access, GitHub down)
 * is not recoverable and propagates, so the run fails instead of quietly returning less.
 */
public final class RunFileCache {

    /** Text, or why there is none. originalBytes is what was downloaded (0 if nothing was). */
    public record Fetched(String text, long originalBytes, String skipReason) {

        public boolean ok() {
            return text != null;
        }
    }

    private final SourceSnapshot snapshot;
    private final Map<String, Fetched> cache = new HashMap<>();
    private int filesFetched;
    private long bytesFetched;

    public RunFileCache(SourceSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public Fetched fetch(String path) {
        Fetched cached = cache.get(path);
        if (cached != null) {
            return cached;
        }
        Fetched result;
        try {
            byte[] bytes = snapshot.read(path);
            filesFetched++;
            bytesFetched += bytes.length;
            SourceDecoder.Decoded decoded = SourceDecoder.decode(bytes);
            result = new Fetched(decoded.text(), bytes.length, decoded.skipReason());
        } catch (ApiException e) {
            if (!"SOURCE_FILE_NOT_FOUND".equals(e.getCode())) {
                throw e;
            }
            result = new Fetched(null, 0, "File not found at the analysed commit");
        }
        cache.put(path, result);
        return result;
    }

    public String commitSha() {
        return snapshot.commitSha();
    }

    public boolean pinnedAtImport() {
        return snapshot.pinnedAtImport();
    }

    public int filesFetched() {
        return filesFetched;
    }

    public long bytesFetched() {
        return bytesFetched;
    }
}
