package com.engineeringlens.analysis.context;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;

/** Assigns roles from path conventions: folder names, file names and file-name words. */
final class FileRoles {

    private FileRoles() {
    }

    private static final Set<String> CONTROLLER_FOLDERS = Set.of("controllers", "controller", "routes", "routers", "router",
            "handlers", "endpoints", "api");
    private static final Set<String> SERVICE_FOLDERS = Set.of("services", "service", "usecases", "use_cases");
    private static final Set<String> DATA_FOLDERS = Set.of("repositories", "repository", "dao", "daos", "persistence");
    private static final Set<String> MODEL_FOLDERS = Set.of("models", "model", "entities", "entity", "domain");
    private static final Set<String> SCHEMA_FOLDERS = Set.of("schemas", "schema", "dto", "dtos");
    private static final Set<String> SECURITY_WORDS = Set.of("auth", "oauth", "jwt", "security", "permission", "permissions",
            "login", "password", "csrf", "cors");
    private static final java.util.regex.Pattern TOOLING_CONFIG = java.util.regex.Pattern.compile(
            "tsconfig(\\.[\\w-]+)?\\.json|\\.eslintrc(\\.\\w+)?|eslint\\.config\\.\\w+|\\.prettierrc(\\.\\w+)?|babel\\.config\\.\\w+"
                    + "|(vite|tailwind|postcss|webpack|rollup|next|nuxt|svelte|astro|turbo|biome)\\.config\\.(js|ts|mjs|cjs|json)");
    private static final Set<String> DATABASE_WORDS = Set.of("db", "database", "datasource", "prisma", "alembic", "orm",
            "typeorm", "sequelize", "knex", "mongo", "mongoose", "redis", "sqlalchemy");
    private static final Set<String> SERVED_ASSET_FOLDERS = Set.of("public", "static", "wwwroot");
    private static final Set<String> TEST_SUPPORT_FOLDERS = Set.of("mocks", "__mocks__", "testing", "fixtures", "test-utils",
            "test_utils", "testutils");
    private static final Set<String> UTILITY_FOLDERS = Set.of("utils", "util", "helpers", "helper", "lib", "common", "shared");

    static Set<FileRole> of(InventoryFile f) {
        String path = f.path();
        String name = RepoPaths.lowerName(path);
        List<String> folders = RepoPaths.folders(path);
        List<String> words = RepoPaths.nameWords(path);
        boolean code = RepoPaths.isCode(f.language());
        EnumSet<FileRole> roles = EnumSet.noneOf(FileRole.class);

        if (RepoPaths.isTestFile(path, f.language())) {
            roles.add(FileRole.TEST); // a test of a service is a test, not a service
            return roles;
        }
        if (folders.stream().anyMatch(SERVED_ASSET_FOLDERS::contains)) {
            return roles; // public/ and static/ hold files served as-is (often generated), not application code
        }
        if (folders.stream().anyMatch(TEST_SUPPORT_FOLDERS::contains)) {
            roles.add(FileRole.TEST_CONFIG); // a mock database is test infrastructure, not persistence code
            return roles;
        }
        if (name.matches("conftest\\.py|pytest\\.ini|tox\\.ini|setuptests\\.(ts|js)|(jest|vitest|playwright|cypress|karma)\\.conf(ig)?\\.(js|ts|mjs|cjs|json)")) {
            roles.add(FileRole.TEST_CONFIG);
        }
        if (RepoPaths.isEntrypoint(path, f.language())) {
            roles.add(FileRole.ENTRYPOINT);
        }
        if (code && (folders.stream().anyMatch(CONTROLLER_FOLDERS::contains) || words.contains("controller")
                || words.contains("routes") || words.contains("router") || name.matches("(urls|views|routes)\\.py"))) {
            roles.add(FileRole.ROUTE_CONTROLLER);
        }
        if (code && (folders.stream().anyMatch(SERVICE_FOLDERS::contains) || words.contains("service"))) {
            roles.add(FileRole.SERVICE);
        }
        if (code && (folders.stream().anyMatch(DATA_FOLDERS::contains) || words.contains("repository") || words.contains("dao")
                || name.matches("(crud|db|database|repository)\\.py"))) {
            roles.add(FileRole.DATA_ACCESS);
        }
        if ((code && (folders.stream().anyMatch(MODEL_FOLDERS::contains) || words.contains("entity") || words.contains("model")
                || name.equals("models.py"))) || name.equals("schema.prisma")) {
            roles.add(FileRole.MODEL);
        }
        if (code && (folders.stream().anyMatch(SCHEMA_FOLDERS::contains) || words.contains("dto") || name.equals("schemas.py"))) {
            roles.add(FileRole.SCHEMA_DTO);
        }
        if (RepoPaths.isMigration(path)) {
            roles.add(FileRole.MIGRATION);
        }
        if (RepoPaths.isEnvTemplate(path)) {
            roles.add(FileRole.ENV_TEMPLATE);
        } else if (TOOLING_CONFIG.matcher(name).matches()) {
            roles.add(FileRole.TOOLING_CONFIG); // how code is built and linted, not how the app runs
        } else if (RepoPaths.isConfigFile(path) && !roles.contains(FileRole.TEST_CONFIG)) {
            roles.add(FileRole.CONFIG);
        }
        if (name.matches("application(-[\\w-]+)?\\.(properties|ya?ml)|settings\\.py|schema\\.prisma|alembic\\.ini|ormconfig\\.\\w+|knexfile\\.\\w+")
                || words.stream().anyMatch(DATABASE_WORDS::contains) && (code || RepoPaths.isConfigFile(path))) {
            roles.add(FileRole.DATABASE_CONFIG);
        }
        if (RepoPaths.isManifest(path)) {
            roles.add(FileRole.BUILD_MANIFEST);
        }
        if (RepoPaths.isDockerfile(path) || RepoPaths.isComposeFile(path)) {
            roles.add(FileRole.CONTAINER);
        }
        if (RepoPaths.ciProvider(path) != null) {
            roles.add(FileRole.CI);
        }
        if (RepoPaths.isDeploymentConfig(path)) {
            roles.add(FileRole.DEPLOYMENT);
        }
        if (code && (words.contains("exception") || words.contains("exceptions") || words.contains("error") || words.contains("errors"))) {
            roles.add(FileRole.ERROR_HANDLING);
        }
        if (code && (folders.stream().anyMatch(SECURITY_WORDS::contains) || words.stream().anyMatch(SECURITY_WORDS::contains))) {
            roles.add(FileRole.SECURITY);
        }
        if (words.contains("logging") || words.contains("logger") || name.matches("logback.*\\.xml|log4j2?.*\\.(xml|properties)")) {
            roles.add(FileRole.LOGGING);
        }
        if (code && (folders.contains("middleware") || folders.contains("middlewares") || words.contains("middleware"))) {
            roles.add(FileRole.MIDDLEWARE);
        }
        if (code && folders.stream().anyMatch(UTILITY_FOLDERS::contains)) {
            roles.add(FileRole.UTILITY);
        }
        if (name.matches(".+\\.(tsx|jsx|vue|svelte)") || (code && folders.contains("components"))) {
            roles.add(FileRole.UI_COMPONENT);
        }
        if (code && !roles.contains(FileRole.ROUTE_CONTROLLER) && (words.contains("api") || words.contains("client"))) {
            roles.add(FileRole.API_CLIENT);
        }
        if (RepoPaths.isDocumentation(path)) {
            roles.add(FileRole.DOCUMENTATION);
        }
        if (code) {
            roles.add(FileRole.SOURCE);
        }
        return roles;
    }
}
