package com.engineeringlens.scenario.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * javac quotes the offending source line under each error. When the user's change breaks code the hidden
 * checks call, those quoted lines would reveal the checks. This keeps every diagnostic about the user's own
 * files as-is and reduces diagnostics in Engiens files to their description (e.g. "cannot find symbol",
 * "required: String,int"), without the source line.
 */
final class CompilerOutput {

    private static final Pattern DIAGNOSTIC = Pattern.compile("^(?:\\./)?(.+?\\.java):(\\d+): (error|warning): (.*)$");
    private static final Pattern DETAIL = Pattern.compile("^\\s+(symbol|location|required|found|reason):.*$");

    private CompilerOutput() {
    }

    static String redact(String javac) {
        List<String> kept = new ArrayList<>();
        boolean hidden = false;
        for (String line : javac.lines().toList()) {
            Matcher m = DIAGNOSTIC.matcher(line);
            if (m.matches()) {
                hidden = isEngiensFile(m.group(1));
                kept.add(hidden ? "hidden checks: " + m.group(3) + ": " + m.group(4) : m.group(1) + ":" + m.group(2) + ": " + m.group(3) + ": " + m.group(4));
            } else if (!hidden || DETAIL.matcher(line).matches() || line.matches("^\\d+ errors?$")) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }

    /** The first error, as one line: what the user should fix first. */
    static String firstError(String redacted) {
        List<String> lines = redacted.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(": error: ")) {
                StringBuilder s = new StringBuilder(line);
                for (int j = i + 1; j < lines.size() && j <= i + 6 && !lines.get(j).contains(": error: "); j++) {
                    if (DETAIL.matcher(lines.get(j)).matches()) {
                        s.append("; ").append(lines.get(j).strip().replaceAll("\\s+", " "));
                    }
                }
                return s.length() <= 1000 ? s.toString() : s.substring(0, 1000);
            }
        }
        return "Compilation failed";
    }

    private static boolean isEngiensFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.startsWith("Engiens");
    }
}
