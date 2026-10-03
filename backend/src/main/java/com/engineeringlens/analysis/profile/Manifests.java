package com.engineeringlens.analysis.profile;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.engineeringlens.analysis.common.RepoPaths;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads declared dependency names out of manifest files. Deliberately small parsers: exact for JSON
 * (package.json), line-based for the rest. Names are lower-cased; JVM entries are stored both as
 * "group:artifact" and "artifact".
 */
final class Manifests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Manifests() {
    }

    /** One parsed manifest. declaredCount is null when the format isn't counted reliably. */
    record Manifest(String path, String ecosystem, Set<String> names, Integer declaredCount, boolean poetry) {

        boolean declares(String name) {
            return names.contains(name);
        }
    }

    static String ecosystemOf(String path) {
        String name = RepoPaths.lowerName(path);
        if (name.equals("package.json")) {
            return "npm";
        }
        if (name.equals("pyproject.toml") || name.equals("pipfile") || RepoPaths.isRequirementsFile(path)) {
            return "python";
        }
        if (name.equals("pom.xml") || name.startsWith("build.gradle")) {
            return "jvm";
        }
        if (name.equals("go.mod")) {
            return "go";
        }
        if (name.equals("cargo.toml")) {
            return "cargo";
        }
        return null;
    }

    static Manifest parse(String path, String text) {
        String name = RepoPaths.lowerName(path);
        String ecosystem = ecosystemOf(path);
        Set<String> names = new TreeSet<>();
        Integer count = null;
        boolean poetry = false;
        if (name.equals("package.json")) {
            count = packageJson(text, names);
        } else if (RepoPaths.isRequirementsFile(path)) {
            count = requirements(text, names);
        } else if (name.equals("pyproject.toml")) {
            poetry = text.contains("[tool.poetry");
            pyproject(text, names);
            count = names.size();
        } else if (name.equals("pipfile")) {
            count = tomlSections(text, names, Set.of("packages", "dev-packages"));
        } else if (name.equals("pom.xml")) {
            count = pom(text, names);
        } else if (name.startsWith("build.gradle")) {
            count = gradle(text, names);
        } else if (name.equals("go.mod")) {
            count = goMod(text, names);
        } else if (name.equals("cargo.toml")) {
            count = tomlSections(text, names, Set.of("dependencies", "dev-dependencies", "build-dependencies"));
        }
        return new Manifest(path, ecosystem, names, count, poetry);
    }

    private static Integer packageJson(String text, Set<String> names) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (RuntimeException e) {
            return null; // unparseable JSON: no names, no count
        }
        int count = 0;
        for (String section : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
            JsonNode deps = root.path(section);
            if (deps.isObject()) {
                for (String dep : deps.propertyNames()) {
                    names.add(dep.toLowerCase(Locale.ROOT));
                    if (!section.equals("peerDependencies") && !section.equals("optionalDependencies")) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static final Pattern REQUIREMENT = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)");

    private static Integer requirements(String text, Set<String> names) {
        int count = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue; // comments and pip options like -r, -e, --index-url
            }
            Matcher m = REQUIREMENT.matcher(trimmed);
            if (m.find()) {
                names.add(normalisePython(m.group(1)));
                count++;
            }
        }
        return count;
    }

    private static final Pattern QUOTED = Pattern.compile("[\"']([A-Za-z0-9][A-Za-z0-9._-]*)[^\"']*[\"']");
    private static final Pattern TOML_KEY = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*=");

    /** PEP 621 dependency arrays and Poetry dependency tables. Not counted: arrays can span many styles. */
    private static void pyproject(String text, Set<String> names) {
        String section = "";
        boolean inArray = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[") && !inArray) {
                section = trimmed.replaceAll("[\\[\\]\\s]", "").toLowerCase(Locale.ROOT);
                continue;
            }
            boolean depSection = section.equals("project") || section.equals("project.optional-dependencies")
                    || section.equals("dependency-groups");
            if (depSection && !inArray && trimmed.matches("^[\\w.-]+\\s*=\\s*\\[.*")) {
                String key = trimmed.substring(0, trimmed.indexOf('=')).strip();
                inArray = !section.equals("project") || key.equals("dependencies");
                if (!inArray) {
                    continue;
                }
                trimmed = trimmed.substring(trimmed.indexOf('[') + 1);
            }
            if (inArray) {
                Matcher m = QUOTED.matcher(trimmed);
                while (m.find()) {
                    names.add(normalisePython(m.group(1)));
                }
                // Only a bracket outside quotes ends the array: "fastapi[standard]" contains one inside.
                if (trimmed.replaceAll("\"[^\"]*\"|'[^']*'", "").contains("]")) {
                    inArray = false;
                }
            } else if (section.startsWith("tool.poetry") && section.endsWith("dependencies")) {
                Matcher m = TOML_KEY.matcher(trimmed);
                if (m.find() && !m.group(1).equalsIgnoreCase("python")) {
                    names.add(normalisePython(m.group(1)));
                }
            }
        }
    }

    /** Keys inside the given [sections], e.g. Pipfile [packages] or Cargo [dependencies]. */
    private static Integer tomlSections(String text, Set<String> names, Set<String> sections) {
        String section = "";
        int count = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[")) {
                section = trimmed.replaceAll("[\\[\\]\\s]", "").toLowerCase(Locale.ROOT);
                continue;
            }
            if (sections.contains(section)) {
                Matcher m = TOML_KEY.matcher(trimmed);
                if (m.find()) {
                    names.add(normalisePython(m.group(1)));
                    count++;
                }
            }
        }
        return count;
    }

    private static final Pattern POM_DEPENDENCY = Pattern.compile(
            "<dependency>.*?<groupId>\\s*([^<\\s]+)\\s*</groupId>.*?<artifactId>\\s*([^<\\s]+)\\s*</artifactId>.*?</dependency>",
            Pattern.DOTALL);
    private static final Pattern POM_PARENT = Pattern.compile(
            "<parent>.*?<groupId>\\s*([^<\\s]+)\\s*</groupId>.*?<artifactId>\\s*([^<\\s]+)\\s*</artifactId>", Pattern.DOTALL);

    private static Integer pom(String text, Set<String> names) {
        int count = 0;
        Matcher m = POM_DEPENDENCY.matcher(text);
        while (m.find()) {
            addJvm(names, m.group(1), m.group(2));
            count++;
        }
        Matcher parent = POM_PARENT.matcher(text);
        if (parent.find()) {
            addJvm(names, parent.group(1), parent.group(2)); // e.g. spring-boot-starter-parent (not counted)
        }
        return count;
    }

    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor"
                    + "|developmentOnly|kapt|testCompileOnly)\\s*\\(?\\s*[\"']([^\"':]+):([^\"':]+)");
    private static final Pattern GRADLE_SPRING_PLUGIN = Pattern.compile("org\\.springframework\\.boot");

    private static Integer gradle(String text, Set<String> names) {
        int count = 0;
        Matcher m = GRADLE_DEPENDENCY.matcher(text);
        while (m.find()) {
            addJvm(names, m.group(2), m.group(3));
            count++;
        }
        if (GRADLE_SPRING_PLUGIN.matcher(text).find()) {
            names.add("org.springframework.boot:plugin");
        }
        return count;
    }

    private static Integer goMod(String text, Set<String> names) {
        int count = 0;
        boolean inBlock = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("require (")) {
                inBlock = true;
            } else if (inBlock && trimmed.equals(")")) {
                inBlock = false;
            } else if (inBlock || trimmed.startsWith("require ")) {
                String spec = inBlock ? trimmed : trimmed.substring("require ".length()).strip();
                if (!spec.isEmpty() && !spec.startsWith("//")) {
                    names.add(spec.split("\\s+")[0].toLowerCase(Locale.ROOT));
                    count++;
                }
            }
        }
        return count;
    }

    private static void addJvm(Set<String> names, String group, String artifact) {
        names.add((group + ":" + artifact).toLowerCase(Locale.ROOT));
        names.add(artifact.toLowerCase(Locale.ROOT));
    }

    /** PEP 503: names compare case-insensitively with "_" and "." equivalent to "-". */
    static String normalisePython(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[._]+", "-");
    }
}
