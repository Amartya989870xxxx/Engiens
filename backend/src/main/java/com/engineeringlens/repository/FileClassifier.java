package com.engineeringlens.repository;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides, from a path and size alone, whether a file is worth analysing later and, if not, why.
 * Deliberately simple and explainable: folder names, extensions and a size cap. Configuration,
 * build files, migrations, CI workflows and docs stay relevant because they show how a project
 * is built and run.
 */
final class FileClassifier {

    static final long MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_PATH_LENGTH = 2048;
    private static final int MAX_EXTENSION_LENGTH = 20;

    /** Folder name → why everything inside it is skipped. Matched on any folder in the path. */
    private static final Map<String, String> IGNORED_FOLDERS = Map.ofEntries(
            Map.entry(".git", "Version control metadata"),
            Map.entry("node_modules", "Dependency directory"),
            Map.entry("bower_components", "Dependency directory"),
            Map.entry("vendor", "Dependency directory"),
            Map.entry(".venv", "Dependency directory"),
            Map.entry("venv", "Dependency directory"),
            Map.entry("dist", "Build output"),
            Map.entry("build", "Build output"),
            Map.entry("target", "Build output"),
            Map.entry("out", "Build output"),
            Map.entry(".next", "Build output"),
            Map.entry(".nuxt", "Build output"),
            Map.entry(".gradle", "Build output"),
            Map.entry("coverage", "Test coverage report"),
            Map.entry("htmlcov", "Test coverage report"),
            Map.entry("__pycache__", "Cache"),
            Map.entry(".pytest_cache", "Cache"),
            Map.entry(".mypy_cache", "Cache"),
            Map.entry(".idea", "Editor settings"),
            Map.entry(".vscode", "Editor settings"));

    private static final Map<String, String> BINARY_EXTENSIONS = binaryExtensions();

    /** SVGs are code-like text, but inside these folders they are clearly image assets. */
    private static final Set<String> ASSET_FOLDERS = Set.of("assets", "images", "img", "icons", "public", "static", "media");

    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"), Map.entry("scala", "Scala"),
            Map.entry("py", "Python"), Map.entry("ipynb", "Jupyter Notebook"), Map.entry("rb", "Ruby"), Map.entry("php", "PHP"),
            Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"), Map.entry("mjs", "JavaScript"), Map.entry("cjs", "JavaScript"),
            Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("vue", "Vue"), Map.entry("svelte", "Svelte"),
            Map.entry("go", "Go"), Map.entry("rs", "Rust"), Map.entry("c", "C"), Map.entry("h", "C"),
            Map.entry("cpp", "C++"), Map.entry("cc", "C++"), Map.entry("hpp", "C++"), Map.entry("cs", "C#"),
            Map.entry("swift", "Swift"), Map.entry("m", "Objective-C"), Map.entry("dart", "Dart"), Map.entry("ex", "Elixir"),
            Map.entry("exs", "Elixir"), Map.entry("hs", "Haskell"), Map.entry("lua", "Lua"), Map.entry("r", "R"),
            Map.entry("sql", "SQL"), Map.entry("sh", "Shell"), Map.entry("bash", "Shell"), Map.entry("ps1", "PowerShell"),
            Map.entry("html", "HTML"), Map.entry("css", "CSS"), Map.entry("scss", "SCSS"), Map.entry("sass", "SCSS"),
            Map.entry("md", "Markdown"), Map.entry("json", "JSON"), Map.entry("yml", "YAML"), Map.entry("yaml", "YAML"),
            Map.entry("xml", "XML"), Map.entry("toml", "TOML"), Map.entry("properties", "Properties"), Map.entry("gradle", "Gradle"),
            Map.entry("tf", "Terraform"), Map.entry("proto", "Protocol Buffers"), Map.entry("graphql", "GraphQL"),
            Map.entry("svg", "SVG"));

    /** Files whose name, not extension, tells us what they are. */
    private static final Map<String, String> LANGUAGE_BY_FILE_NAME = Map.of(
            "dockerfile", "Docker", "makefile", "Makefile", "gemfile", "Ruby", "procfile", "Procfile",
            "jenkinsfile", "Groovy");

    private FileClassifier() {
    }

    record Classification(String path, String fileName, String extension, String language, long sizeBytes,
            boolean ignored, String ignoreReason) {
    }

    static Classification classify(String rawPath, long sizeBytes) {
        String path = rawPath.length() > MAX_PATH_LENGTH ? rawPath.substring(0, MAX_PATH_LENGTH) : rawPath;
        String[] parts = path.split("/");
        String fileName = parts[parts.length - 1];
        String extension = extensionOf(fileName);
        String language = languageOf(fileName, extension);

        String reason = ignoreReason(rawPath, parts, fileName, extension, sizeBytes);
        return new Classification(path, truncate(fileName, 255), extension, language, sizeBytes, reason != null, reason);
    }

    private static String ignoreReason(String rawPath, String[] parts, String fileName, String extension, long size) {
        if (rawPath.length() > MAX_PATH_LENGTH) {
            return "Path too long";
        }
        // Folders: every part except the last, which is the file itself.
        for (int i = 0; i < parts.length - 1; i++) {
            String folderReason = IGNORED_FOLDERS.get(parts[i]);
            if (folderReason != null) {
                return folderReason;
            }
        }
        if (fileName.equals(".DS_Store") || fileName.equalsIgnoreCase("Thumbs.db")) {
            return "Operating system file";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css") || lower.endsWith(".map")) {
            return "Generated or minified file";
        }
        if (extension != null) {
            String binaryReason = BINARY_EXTENSIONS.get(extension);
            if (binaryReason != null) {
                return binaryReason;
            }
            if (extension.equals("svg") && inAssetFolder(parts)) {
                return "Image asset";
            }
        }
        if (size > MAX_FILE_BYTES) {
            return "Larger than 1 MB";
        }
        return null;
    }

    private static boolean inAssetFolder(String[] parts) {
        for (int i = 0; i < parts.length - 1; i++) {
            if (ASSET_FOLDERS.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** "app.test.ts" → "ts"; ".env.example" → "example"; "Dockerfile" → null. */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null; // no dot, a dotfile like ".gitignore", or a trailing dot
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.length() > MAX_EXTENSION_LENGTH ? null : extension;
    }

    private static String languageOf(String fileName, String extension) {
        String byName = LANGUAGE_BY_FILE_NAME.get(fileName.toLowerCase(Locale.ROOT));
        if (byName != null) {
            return byName;
        }
        return extension == null ? null : LANGUAGE_BY_EXTENSION.get(extension);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static Map<String, String> binaryExtensions() {
        Map<String, String> reasons = new java.util.HashMap<>();
        for (String e : new String[] { "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tif", "tiff", "avif", "heic", "psd" }) {
            reasons.put(e, "Image asset");
        }
        for (String e : new String[] { "mp3", "wav", "ogg", "flac", "m4a", "aac" }) {
            reasons.put(e, "Audio file");
        }
        for (String e : new String[] { "mp4", "mov", "avi", "mkv", "webm", "wmv" }) {
            reasons.put(e, "Video file");
        }
        for (String e : new String[] { "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "ear" }) {
            reasons.put(e, "Archive");
        }
        for (String e : new String[] { "exe", "dll", "so", "dylib", "bin", "class", "o", "a", "obj", "pyc", "pyo", "wasm" }) {
            reasons.put(e, "Compiled binary");
        }
        for (String e : new String[] { "woff", "woff2", "ttf", "otf", "eot" }) {
            reasons.put(e, "Font file");
        }
        for (String e : new String[] { "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx" }) {
            reasons.put(e, "Document");
        }
        for (String e : new String[] { "db", "sqlite", "sqlite3", "parquet", "pkl", "h5", "onnx", "pt", "ckpt" }) {
            reasons.put(e, "Data or model file");
        }
        return Map.copyOf(reasons);
    }
}
