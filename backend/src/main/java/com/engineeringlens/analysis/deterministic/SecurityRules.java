package com.engineeringlens.analysis.deterministic;

import static com.engineeringlens.analysis.deterministic.Signal.evidence;

import java.util.ArrayList;
import java.util.List;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;
import com.engineeringlens.analysis.common.ReviewDimension;

/** Obvious, high-confidence secret handling signals only. Not a security audit. */
public final class SecurityRules {

    private SecurityRules() {
    }

    public static final AnalysisRule SECRET_FILE = AnalysisRule.of("SECURITY.SECRET_FILE_COMMITTED", ReviewDimension.SECURITY,
            "Committed files that conventionally hold secrets (.env, private keys, keystores). Their contents are never read.",
            (rule, in) -> in.relevant().stream().map(InventoryFile::path).filter(RepoPaths::isSecretLike)
                    .map(p -> Signal.of(rule, Severity.MEDIUM, Confidence.HIGH,
                            p + " is committed. Files like this usually hold secrets; its contents were not read.",
                            evidence("path", p)).at(p, null))
                    .toList());

    private static final int MAX_PER_FILE = 3;

    public static final AnalysisRule SECRET_PATTERN = AnalysisRule.of("SECURITY.SECRET_PATTERN", ReviewDimension.SECURITY,
            "Well-known credential formats (AWS keys, GitHub tokens, private key blocks...) in inspected files. Values are never stored.",
            (rule, in) -> {
                List<Signal> signals = new ArrayList<>();
                in.texts().all().forEach((path, text) -> SecretScanner.scan(text).stream().limit(MAX_PER_FILE)
                        .forEach(m -> signals.add(Signal.of(rule, Severity.HIGH, Confidence.HIGH,
                                "A value that looks like a " + m.kind() + " appears in " + path + " at line " + m.line()
                                        + ". The value itself is not stored.",
                                evidence("kind", m.kind(), "path", path, "line", m.line())).at(path, m.line()))));
                return signals;
            });

    public static final AnalysisRule GITIGNORE = AnalysisRule.of("SECURITY.GITIGNORE", ReviewDimension.SECURITY,
            "Whether a .gitignore exists at the repository root.",
            (rule, in) -> in.inventory().stream().anyMatch(f -> f.path().equals(".gitignore")) ? List.of()
                    : List.of(Signal.of(rule, Severity.LOW, Confidence.HIGH,
                            "No .gitignore at the repository root, which makes it easier to commit build output or secrets by accident.",
                            evidence("gitignore", false))));

    static final List<AnalysisRule> ALL = List.of(SECRET_FILE, SECRET_PATTERN, GITIGNORE);
}
