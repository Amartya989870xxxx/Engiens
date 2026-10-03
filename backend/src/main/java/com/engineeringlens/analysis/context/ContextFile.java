package com.engineeringlens.analysis.context;

import java.util.List;

import com.engineeringlens.analysis.common.ReviewDimension;

/**
 * One selected file as recorded in the manifest: why it was chosen and exactly how much of it a
 * reviewer gets. Contents are not part of this record (and are not persisted).
 *
 * @param includedBytes  bytes of content included (0 when skipped or withheld)
 * @param originalBytes  bytes of the file as downloaded, or as listed in the inventory if not downloaded
 * @param sha256         hash of the included content, so a later re-fetch can prove it got the same text
 * @param note           why content was truncated, skipped or withheld; null when included in full
 */
public record ContextFile(String path, String language, long sizeBytes, String relevance, double score, List<String> roles,
        List<String> reasons, List<ReviewDimension> dimensions, ContentStatus contentStatus, long includedBytes,
        long originalBytes, String sha256, String note) {

    public enum ContentStatus {
        /** Whole file included. */
        INCLUDED,
        /** Only the beginning is included; see note. */
        TRUNCATED,
        /** Not included: too large, unreadable, missing, or over budget; see note. */
        SKIPPED,
        /** Not included because it appears to contain a credential. */
        WITHHELD
    }
}
