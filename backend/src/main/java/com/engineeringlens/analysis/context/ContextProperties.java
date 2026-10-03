package com.engineeringlens.analysis.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits for what analysis fetches and hands to a reviewer. Conservative defaults; tune with
 * analysis.context.* properties after measuring real repositories.
 *
 * @param maxFilesPerDimension most files any one review dimension may select
 * @param maxContextFiles      most distinct files in the whole context
 * @param maxFileBytes         most bytes of one file included in the context (longer files are truncated)
 * @param maxFetchBytes        files larger than this are not downloaded at all
 * @param maxTotalBytes        total bytes of file content across the whole context
 * @param profileMaxFiles      most manifest/config files the profiler reads
 * @param profileMaxFileBytes  largest manifest/config file the profiler reads
 */
@ConfigurationProperties("analysis.context")
public record ContextProperties(
        @DefaultValue("8") int maxFilesPerDimension,
        @DefaultValue("40") int maxContextFiles,
        @DefaultValue("16384") int maxFileBytes,
        @DefaultValue("262144") long maxFetchBytes,
        @DefaultValue("262144") long maxTotalBytes,
        @DefaultValue("20") int profileMaxFiles,
        @DefaultValue("200000") long profileMaxFileBytes) {

    public static ContextProperties defaults() {
        return new ContextProperties(8, 40, 16384, 262144, 262144, 20, 200000);
    }

    /** Identifies a configuration, so a stored context can say exactly which limits produced it. */
    public String fingerprint() {
        return "perDim=" + maxFilesPerDimension + ",files=" + maxContextFiles + ",fileBytes=" + maxFileBytes + ",fetchBytes="
                + maxFetchBytes + ",totalBytes=" + maxTotalBytes + ",profileFiles=" + profileMaxFiles + ",profileBytes="
                + profileMaxFileBytes;
    }
}
