package com.engineeringlens.analysis.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Path conventions shared by the profiler, the rules and the context selector, so they never disagree
 * about what a "test file" or a "secret-like file" is. Everything here works from the path alone.
 */
public final class RepoPaths {

    private RepoPaths() {
    }

    public static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public static String lowerName(String path) {
        return fileName(path).toLowerCase(Locale.ROOT);
    }

    /** Folder names on the way to the file, lower-cased: "src/Main/App.java" → [src, main]. */
    public static List<String> folders(String path) {
        String[] parts = path.toLowerCase(Locale.ROOT).split("/");
        List<String> folders = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length - 1; i++) {
            folders.add(parts[i]);
        }
        return folders;
    }

    public static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    public static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    public static boolean isRoot(String path) {
        return path.indexOf('/') < 0;
    }

    /** Words in a file name: "OrderServiceImpl.java" → [order, service, impl]; "auth_utils.py" → [auth, utils]. */
    public static List<String> nameWords(String path) {
        String name = fileName(path);
        int dot = name.indexOf('.', 1);
        String stem = dot > 0 ? name.substring(0, dot) : name;
        List<String> words = new ArrayList<>();
        for (String w : stem.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (!w.isEmpty()) {
                words.add(w.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    // ---- languages -----------------------------------------------------------------------------

    /** Languages that are programming languages (code), as opposed to data, config or markup. */
    private static final Set<String> CODE_LANGUAGES = Set.of("Java", "Kotlin", "Scala", "Python", "Ruby", "PHP",
            "JavaScript", "TypeScript", "Vue", "Svelte", "Go", "Rust", "C", "C++", "C#", "Swift", "Objective-C", "Dart",
            "Elixir", "Haskell", "Lua", "R", "Shell", "PowerShell", "Groovy", "Jupyter Notebook");

    public static boolean isCode(String language) {
        return language != null && CODE_LANGUAGES.contains(language);
    }

    // ---- tests ---------------------------------------------------------------------------------

    private static final Set<String> TEST_FOLDERS = Set.of("test", "tests", "__tests__", "spec", "specs", "e2e",
            "integration_tests", "integration-tests");
    private static final Pattern TEST_NAME = Pattern.compile(
            "^test_.+\\.py$|.+_test\\.py$|.+\\.(test|spec)\\.(js|jsx|ts|tsx|mjs|cjs|vue)$|.+(Test|Tests|IT)\\.(java|kt|scala|groovy)$"
                    + "|.+_test\\.go$|.+_spec\\.rb$|.+Tests?\\.cs$");

    /** Test infrastructure, not tests: pytest's shared fixtures and Python package markers. */
    private static final Set<String> NOT_TESTS = Set.of("conftest.py", "__init__.py");

    /** Recognised by name (test_x.py, x.test.ts, XTest.java, x_test.go) or by living in a test folder as code. */
    public static boolean isTestFile(String path, String language) {
        if (NOT_TESTS.contains(lowerName(path))) {
            return false;
        }
        if (TEST_NAME.matcher(fileName(path)).matches()) {
            return true;
        }
        return isCode(language) && folders(path).stream().anyMatch(TEST_FOLDERS::contains);
    }

    public static boolean isTestFolder(String folder) {
        return TEST_FOLDERS.contains(folder.toLowerCase(Locale.ROOT));
    }

    // ---- secrets -------------------------------------------------------------------------------

    private static final Set<String> SECRET_NAMES = Set.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", ".netrc", ".pypirc",
            "credentials.json", "serviceaccountkey.json", ".htpasswd");
    private static final Set<String> SECRET_EXTENSIONS = Set.of("pem", "key", "p12", "pfx", "jks", "keystore", "ppk");
    private static final Set<String> ENV_TEMPLATE_SUFFIXES = Set.of("example", "sample", "template", "dist", "defaults");

    /**
     * Files that conventionally hold real secrets: .env (and .env.local etc.), private keys, keystores,
     * credential files. Templates like .env.example are NOT secret-like. These files are never read.
     */
    public static boolean isSecretLike(String path) {
        String name = lowerName(path);
        if (name.equals(".env") || (name.startsWith(".env.") && !isEnvTemplate(path))) {
            return true;
        }
        if (SECRET_NAMES.contains(name) || name.startsWith("service-account") && name.endsWith(".json")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && SECRET_EXTENSIONS.contains(name.substring(dot + 1));
    }

    public static boolean isEnvTemplate(String path) {
        String name = lowerName(path);
        if (!name.startsWith(".env.")) {
            return false;
        }
        return ENV_TEMPLATE_SUFFIXES.contains(name.substring(name.lastIndexOf('.') + 1));
    }

    // ---- build, deploy, config -----------------------------------------------------------------

    private static final Set<String> MANIFESTS = Set.of("package.json", "pyproject.toml", "pipfile", "pom.xml", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "go.mod", "cargo.toml", "composer.json", "gemfile");

    public static boolean isManifest(String path) {
        String name = lowerName(path);
        return MANIFESTS.contains(name) || isRequirementsFile(path);
    }

    public static boolean isRequirementsFile(String path) {
        String name = lowerName(path);
        return name.endsWith(".txt") && (name.startsWith("requirements") || folders(path).contains("requirements"));
    }

    private static final Set<String> LOCKFILES = Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb",
            "bun.lock", "poetry.lock", "pipfile.lock", "uv.lock", "cargo.lock", "go.sum", "composer.lock", "gemfile.lock",
            "npm-shrinkwrap.json");

    public static boolean isLockfile(String path) {
        return LOCKFILES.contains(lowerName(path));
    }

    public static boolean isDockerfile(String path) {
        String name = lowerName(path);
        return name.equals("dockerfile") || name.startsWith("dockerfile.") || name.endsWith(".dockerfile");
    }

    public static boolean isComposeFile(String path) {
        String name = lowerName(path);
        return name.matches("(docker-)?compose(\\.[\\w-]+)?\\.ya?ml");
    }

    /** The CI system a file configures, or null. */
    public static String ciProvider(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lowerName(path);
        if (lower.startsWith(".github/workflows/") && (name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return "GitHub Actions";
        }
        if (lower.equals(".gitlab-ci.yml")) {
            return "GitLab CI";
        }
        if (lower.equals(".circleci/config.yml")) {
            return "CircleCI";
        }
        if (name.equals("jenkinsfile")) {
            return "Jenkins";
        }
        if (lower.equals("azure-pipelines.yml")) {
            return "Azure Pipelines";
        }
        if (lower.equals(".travis.yml")) {
            return "Travis CI";
        }
        if (lower.equals("bitbucket-pipelines.yml")) {
            return "Bitbucket Pipelines";
        }
        return null;
    }

    private static final Set<String> DEPLOY_NAMES = Set.of("vercel.json", "netlify.toml", "fly.toml", "render.yaml", "procfile",
            "app.yaml", "serverless.yml", "serverless.yaml", "heroku.yml", "railway.json", "railway.toml", "chart.yaml",
            "skaffold.yaml", "kustomization.yaml");
    private static final Set<String> INFRA_FOLDERS = Set.of("k8s", "kubernetes", "helm", "charts", "terraform", "infra",
            "infrastructure", "deploy", "deployment", "deployments");

    public static boolean isDeploymentConfig(String path) {
        String name = lowerName(path);
        if (DEPLOY_NAMES.contains(name) || name.endsWith(".tf")) {
            return true;
        }
        return (name.endsWith(".yml") || name.endsWith(".yaml")) && folders(path).stream().anyMatch(INFRA_FOLDERS::contains);
    }

    private static final Pattern CONFIG_NAME = Pattern.compile(
            "application(-[\\w-]+)?\\.(properties|ya?ml)|appsettings(\\.[\\w-]+)?\\.json|settings\\.py|config\\.(py|js|ts|json|ya?ml|toml)"
                    + "|[\\w-]+\\.config\\.(js|ts|mjs|cjs)|tsconfig(\\.[\\w-]+)?\\.json|\\.eslintrc(\\.\\w+)?|logback(-[\\w-]+)?\\.xml"
                    + "|log4j2?(-[\\w-]+)?\\.(xml|properties)|alembic\\.ini|schema\\.prisma|nginx\\.conf");

    public static boolean isConfigFile(String path) {
        String name = lowerName(path);
        if (CONFIG_NAME.matcher(name).matches()) {
            return true;
        }
        return folders(path).stream().anyMatch(f -> f.equals("config") || f.equals("configs"))
                && name.matches(".+\\.(ya?ml|json|toml|properties|ini|py|js|ts)");
    }

    // ---- application shape ---------------------------------------------------------------------

    private static final Pattern ENTRYPOINT_NAME = Pattern.compile(
            "main\\.py|app\\.py|manage\\.py|wsgi\\.py|asgi\\.py|server\\.(js|ts|mjs)|app\\.(js|ts|mjs)|main\\.(ts|tsx|js|jsx|go|rs|kt)"
                    + "|program\\.cs|[a-z0-9]*application\\.(java|kt)");

    /** Conventional application entry points: main.py, manage.py, server.ts, main.go, *Application.java… */
    public static boolean isEntrypoint(String path, String language) {
        if (!isCode(language) || isTestFile(path, language)) {
            return false;
        }
        String name = lowerName(path);
        if (ENTRYPOINT_NAME.matcher(name).matches()) {
            // "SomethingApplication.java" must be the Spring-style main class, not e.g. "LoanApplication" in a model package.
            return !name.endsWith("application.java") && !name.endsWith("application.kt")
                    || folders(path).stream().noneMatch(f -> f.equals("model") || f.equals("models") || f.equals("entity")
                            || f.equals("domain") || f.equals("dto"));
        }
        // index.js/ts only counts at the root or directly in src/.
        return name.matches("index\\.(js|ts|mjs)") && (isRoot(path) || parent(path).toLowerCase(Locale.ROOT).matches("(.*/)?src"));
    }

    public static boolean isMigration(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        boolean inMigrations = folders(path).contains("migrations") || lower.contains("db/migration/")
                || lower.contains("alembic/versions/") || lower.contains("prisma/migrations/") || lower.contains("db/changelog/");
        return inMigrations && lowerName(path).matches(".+\\.(sql|py|ts|js|xml|ya?ml)");
    }

    public static boolean isReadme(String path) {
        return isRoot(path) && lowerName(path).startsWith("readme");
    }

    public static boolean isDocumentation(String path) {
        String name = lowerName(path);
        boolean docType = name.endsWith(".md") || name.endsWith(".rst") || name.endsWith(".adoc") || name.endsWith(".txt");
        List<String> folders = folders(path);
        return docType && (isReadme(path) || (!folders.isEmpty() && (folders.get(0).equals("docs") || folders.get(0).equals("doc"))));
    }
}
