package com.engineeringlens.analysis.deterministic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds strings that are almost certainly real credentials: well-known token formats with fixed
 * prefixes and lengths. Not a general secret scanner; generic words like "password" are ignored
 * because they produce far more false accusations than real finds. Matched values are never returned.
 */
public final class SecretScanner {

    private record Kind(String name, Pattern pattern) {
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("AWS access key ID", Pattern.compile("(?<![A-Z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![A-Z0-9])")),
            new Kind("GitHub token", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\\b")),
            new Kind("private key", Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----")),
            new Kind("Slack token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
            new Kind("Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
            new Kind("Stripe live secret key", Pattern.compile("\\bsk_live_[0-9a-zA-Z]{24,}\\b")),
            new Kind("OpenAI API key", Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{40,}\\b")));

    private SecretScanner() {
    }

    /** Where a credential-like value appears: its kind and 1-based line. Never the value itself. */
    public record Match(String kind, int line) {
    }

    public static List<Match> scan(String text) {
        List<Match> matches = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Kind kind : KINDS) {
                Matcher m = kind.pattern().matcher(lines[i]);
                while (m.find()) {
                    if (!looksLikePlaceholder(m.group())) {
                        matches.add(new Match(kind.name(), i + 1));
                        break;
                    }
                }
            }
        }
        return matches;
    }

    /** Documentation examples such as AKIAIOSFODNN7EXAMPLE, or "xxxx" placeholders. */
    private static boolean looksLikePlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("example") || lower.contains("xxxx") || lower.contains("your") || lower.contains("dummy")
                || value.chars().distinct().count() <= 3;
    }
}
