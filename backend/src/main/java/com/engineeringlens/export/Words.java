package com.engineeringlens.export;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.RubricDimension;

/** How stored values read in a document: enum names as words, dates, evidence references. */
final class Words {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private Words() {
    }

    /** NOT_ASSESSABLE → "Not assessable". */
    static String of(Enum<?> value) {
        if (value == null) {
            return "—";
        }
        String s = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A rubric id such as ARCHITECTURE_AND_MODULARITY becomes its display name; other text is kept as written. */
    static List<String> areas(List<String> values) {
        return values.stream().map(v -> {
            try {
                return RubricDimension.valueOf(v.strip()).displayName();
            } catch (IllegalArgumentException e) {
                return v;
            }
        }).toList();
    }

    static String date(Instant instant) {
        return instant == null ? "—" : DATE.format(instant) + " (UTC)";
    }

    static String shortSha(String sha) {
        return sha == null ? "—" : sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    static String evidence(List<ReviewDocument.Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        return evidence.stream().map(e -> e.file() != null
                ? e.file() + (e.lineStart() == null ? "" : ":" + e.lineStart() + (e.lineEnd() == null || e.lineEnd().equals(e.lineStart()) ? "" : "–" + e.lineEnd()))
                : "Engiens check " + e.signalId()).collect(Collectors.joining(", "));
    }

    /** "foo-bar" from any repository name, for file names. */
    static String slug(String s) {
        String slug = s == null ? "repository" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "repository" : slug;
    }
}
