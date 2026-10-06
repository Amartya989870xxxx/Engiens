package com.engineeringlens.analysis.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.context.ContextFile.ContentStatus;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.deterministic.SecretScanner;
import com.engineeringlens.analysis.profile.RepositoryProfile;
import com.engineeringlens.analysis.source.RunFileCache;

/**
 * Builds the review context: downloads only the selected files, applies size limits, keeps credentials out, and
 * assembles the provider-neutral {@link AnalysisContext}.
 */
@Component
public class ContextBuilder {

    /** Below this much remaining budget, a file is skipped rather than reduced to a useless sliver. */
    static final int MIN_USEFUL_BYTES = 1024;

    /** Fetched selection: manifest entries, included texts, and the full texts (for the deterministic rules). */
    public record Contents(List<ContextFile> files, Map<String, String> included, SourceTexts fullTexts) {
    }

    public Contents fetch(ContextSelector.Selection selection, RunFileCache cache, ContextProperties limits) {
        List<ContextFile> files = new ArrayList<>();
        Map<String, String> included = new LinkedHashMap<>();
        SourceTexts fullTexts = new SourceTexts();
        long used = 0;
        for (ContextSelector.Candidate c : selection.files()) {
            if (c.file().sizeBytes() > limits.maxFetchBytes()) {
                files.add(entry(c, ContentStatus.SKIPPED, 0, c.file().sizeBytes(), null,
                        "Larger than the " + kb(limits.maxFetchBytes()) + " fetch limit; not downloaded"));
                continue;
            }
            RunFileCache.Fetched fetched = cache.fetch(c.file().path());
            if (!fetched.ok()) {
                files.add(entry(c, ContentStatus.SKIPPED, 0, fetched.originalBytes(), null, fetched.skipReason()));
                continue;
            }
            fullTexts.put(c.file().path(), fetched.text());
            if (!SecretScanner.scan(fetched.text()).isEmpty()) {
                // Kept out of the context entirely; the deterministic rules still see it and raise a signal.
                files.add(entry(c, ContentStatus.WITHHELD, 0, fetched.originalBytes(), null,
                        "Appears to contain a credential; content withheld"));
                continue;
            }
            long remaining = limits.maxTotalBytes() - used;
            if (remaining < MIN_USEFUL_BYTES) {
                files.add(entry(c, ContentStatus.SKIPPED, 0, fetched.originalBytes(), null, "Context size budget reached"));
                continue;
            }
            int allowed = (int) Math.min(limits.maxFileBytes(), remaining);
            String text = firstBytes(fetched.text(), allowed);
            long textBytes = utf8Length(text);
            long fullBytes = utf8Length(fetched.text());
            boolean truncated = textBytes < fullBytes;
            used += textBytes;
            included.put(c.file().path(), text);
            files.add(entry(c, truncated ? ContentStatus.TRUNCATED : ContentStatus.INCLUDED, textBytes, fullBytes, sha256(text),
                    truncated ? "First " + kb(textBytes) + " of " + kb(fullBytes) + " included (cut at a line break)" : null));
        }
        return new Contents(files, included, fullTexts);
    }

    public AnalysisContext assemble(RepositoryProfile profile, DeterministicAnalysis analysis, ContextSelector.Selection selection,
            Contents contents, DeveloperProfile developer, String commitSha, boolean pinnedAtImport, ContextProperties limits) {
        Map<ReviewDimension, ContextManifest.DimensionContext> dimensions = new EnumMap<>(ReviewDimension.class);
        for (ReviewDimension d : ReviewDimension.values()) {
            List<Integer> signalIndexes = new ArrayList<>();
            for (int i = 0; i < analysis.signals().size(); i++) {
                if (analysis.signals().get(i).category() == d) {
                    signalIndexes.add(i);
                }
            }
            dimensions.put(d, new ContextManifest.DimensionContext(selection.dimensions().get(d), signalIndexes));
        }
        List<ContextFile> files = contents.files();
        ContextManifest.Stats stats = new ContextManifest.Stats(selection.filesConsidered(), files.size(),
                count(files, ContentStatus.INCLUDED), count(files, ContentStatus.TRUNCATED), count(files, ContentStatus.SKIPPED),
                count(files, ContentStatus.WITHHELD), files.stream().mapToLong(ContextFile::includedBytes).sum());
        ContextManifest manifest = new ContextManifest(AnalysisContext.SCHEMA_VERSION, commitSha, pinnedAtImport, limits,
                developer, files, dimensions, stats);
        return new AnalysisContext(AnalysisContext.SCHEMA_VERSION, profile, analysis, manifest, contents.included());
    }

    private static ContextFile entry(ContextSelector.Candidate c, ContentStatus status, long includedBytes, long originalBytes,
            String sha256, String note) {
        return new ContextFile(c.file().path(), c.file().language(), c.file().sizeBytes(),
                c.bestScore() >= DimensionWeights.HIGH ? "HIGH" : "MEDIUM", c.bestScore(),
                c.roles().stream().map(Enum::name).toList(), c.reasons(), c.dimensions(), status, includedBytes, originalBytes,
                sha256, note);
    }

    private static int count(List<ContextFile> files, ContentStatus status) {
        return (int) files.stream().filter(f -> f.contentStatus() == status).count();
    }

    /** The longest prefix within maxBytes of UTF-8, cut at the last line break (or a character boundary). */
    public static String firstBytes(String text, int maxBytes) {
        if (utf8Length(text) <= maxBytes) {
            return text;
        }
        int bytes = 0;
        int end = 0;
        int lastBreak = -1;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) {
                break;
            }
            bytes += len;
            end += Character.charCount(cp);
            if (cp == '\n') {
                lastBreak = end;
            }
        }
        return lastBreak > 0 ? text.substring(0, lastBreak) : text.substring(0, end);
    }

    static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String kb(long bytes) {
        return bytes < 1024 ? bytes + " B" : Math.round(bytes / 1024.0) + " KB";
    }
}
