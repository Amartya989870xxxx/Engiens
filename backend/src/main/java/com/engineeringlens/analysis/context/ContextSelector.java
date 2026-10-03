package com.engineeringlens.analysis.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;
import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.deterministic.StructureRules;

/**
 * Chooses, from paths and sizes alone (no downloads), which files a reviewer should read for each
 * dimension, and records why. Fully deterministic: ties break by size, then path.
 */
@Component
public class ContextSelector {

    /** One file chosen for one dimension. */
    public record Pick(String path, double score, String relevance, List<String> reasons) {
    }

    /** A file chosen for at least one dimension, with everything known about it before downloading. */
    public record Candidate(InventoryFile file, Set<FileRole> roles, double bestScore, List<ReviewDimension> dimensions,
            List<String> reasons) {
    }

    public record Selection(List<Candidate> files, Map<ReviewDimension, List<Pick>> dimensions, int filesConsidered) {
    }

    public Selection select(List<InventoryFile> inventory, ContextProperties limits) {
        Map<String, Set<FileRole>> roles = new LinkedHashMap<>();
        Map<String, InventoryFile> byPath = new LinkedHashMap<>();
        for (InventoryFile f : inventory) {
            // Never candidates: skipped files, secret-like files (never read), lockfiles (machine-generated noise).
            if (f.ignored() || RepoPaths.isSecretLike(f.path()) || RepoPaths.isLockfile(f.path())) {
                continue;
            }
            Set<FileRole> r = FileRoles.of(f);
            if (!r.isEmpty()) {
                roles.put(f.path(), r);
                byPath.put(f.path(), f);
            }
        }

        Map<ReviewDimension, List<Pick>> dimensions = new EnumMap<>(ReviewDimension.class);
        Map<String, Double> best = new LinkedHashMap<>();
        Map<String, List<ReviewDimension>> fileDimensions = new LinkedHashMap<>();
        for (ReviewDimension d : ReviewDimension.values()) {
            List<Pick> picks = byPath.values().stream()
                    .map(f -> score(d, f, roles.get(f.path())))
                    .filter(p -> p.score() >= DimensionWeights.THRESHOLD)
                    .sorted(Comparator.comparingDouble(Pick::score).reversed()
                            .thenComparing(p -> -byPath.get(p.path()).sizeBytes())
                            .thenComparing(Pick::path))
                    .limit(limits.maxFilesPerDimension())
                    .toList();
            dimensions.put(d, picks);
            for (Pick p : picks) {
                best.merge(p.path(), p.score(), Math::max);
                fileDimensions.computeIfAbsent(p.path(), k -> new ArrayList<>()).add(d);
            }
        }

        // Distinct files, most valuable first; anything past the overall cap is dropped from every dimension.
        List<String> ordered = best.keySet().stream()
                .sorted(Comparator.comparingDouble((String p) -> best.get(p)).reversed()
                        .thenComparing(p -> -byPath.get(p).sizeBytes())
                        .thenComparing(Comparator.naturalOrder()))
                .limit(limits.maxContextFiles())
                .toList();
        Set<String> kept = Set.copyOf(ordered);
        dimensions.replaceAll((d, picks) -> picks.stream().filter(p -> kept.contains(p.path())).toList());

        List<Candidate> files = ordered.stream().map(path -> {
            Set<FileRole> r = roles.get(path);
            Set<String> reasons = new LinkedHashSet<>();
            r.forEach(role -> reasons.add(role.description()));
            if (isLarge(byPath.get(path))) {
                reasons.add(largeReason(byPath.get(path)));
            }
            List<ReviewDimension> dims = fileDimensions.get(path);
            dims.forEach(d -> reasons.add("relevant to " + label(d) + " review"));
            return new Candidate(byPath.get(path), r, best.get(path), List.copyOf(dims), List.copyOf(reasons));
        }).toList();

        return new Selection(files, dimensions, byPath.size());
    }

    /** Score of one file for one dimension, with the reasons that produced it. */
    static Pick score(ReviewDimension d, InventoryFile f, Set<FileRole> roles) {
        double score = 0;
        List<FileRole> contributing = new ArrayList<>();
        for (FileRole role : roles) {
            double w = DimensionWeights.weight(d, role);
            if (w >= DimensionWeights.THRESHOLD) {
                contributing.add(role);
            }
            score = Math.max(score, w);
        }
        List<String> reasons = new ArrayList<>();
        contributing.stream()
                .sorted(Comparator.comparingDouble((FileRole r) -> DimensionWeights.weight(d, r)).reversed())
                .forEach(r -> reasons.add(r.description()));
        if (score > 0 && DimensionWeights.boostsLargeFiles(d) && isLarge(f)) {
            score = Math.min(1.0, score + DimensionWeights.LARGE_FILE_BONUS);
            reasons.add(largeReason(f));
        }
        reasons.add("relevant to " + label(d) + " review");
        score = Math.round(score * 100) / 100.0;
        return new Pick(f.path(), score, score >= DimensionWeights.HIGH ? "HIGH" : "MEDIUM", reasons);
    }

    private static boolean isLarge(InventoryFile f) {
        return RepoPaths.isCode(f.language()) && f.sizeBytes() > StructureRules.LARGE_SOURCE_FILE_BYTES;
    }

    private static String largeReason(InventoryFile f) {
        return "large source file (" + Math.round(f.sizeBytes() / 1024.0) + " KB)";
    }

    static String label(ReviewDimension d) {
        return d.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
