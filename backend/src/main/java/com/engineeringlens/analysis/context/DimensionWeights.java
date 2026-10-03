package com.engineeringlens.analysis.context;

import static com.engineeringlens.analysis.context.FileRole.*;

import java.util.EnumMap;
import java.util.Map;

import com.engineeringlens.analysis.common.ReviewDimension;

/**
 * How useful each kind of file is as evidence for each review dimension (0 to 1). A file's score for a
 * dimension is the weight of its most relevant role. Files scoring below {@link #THRESHOLD} aren't selected.
 * This table is the whole selection policy: change it here, in one readable place.
 */
final class DimensionWeights {

    static final double THRESHOLD = 0.5;
    static final double HIGH = 0.8;

    private static final Map<ReviewDimension, Map<FileRole, Double>> WEIGHTS = new EnumMap<>(ReviewDimension.class);

    static {
        weights(ReviewDimension.ARCHITECTURE, ENTRYPOINT, 0.95, ROUTE_CONTROLLER, 0.9, SERVICE, 0.9, DATA_ACCESS, 0.85,
                MODEL, 0.7, MIDDLEWARE, 0.7, SCHEMA_DTO, 0.6, CONFIG, 0.6, BUILD_MANIFEST, 0.5, API_CLIENT, 0.5);
        weights(ReviewDimension.CODE_QUALITY, SERVICE, 0.85, ROUTE_CONTROLLER, 0.8, DATA_ACCESS, 0.7, UTILITY, 0.7,
                UI_COMPONENT, 0.65, MODEL, 0.6, MIDDLEWARE, 0.6, ENTRYPOINT, 0.6, API_CLIENT, 0.6, TOOLING_CONFIG, 0.55, SOURCE, 0.5);
        weights(ReviewDimension.ERROR_HANDLING, ERROR_HANDLING, 0.95, MIDDLEWARE, 0.8, ROUTE_CONTROLLER, 0.8, SERVICE, 0.7,
                API_CLIENT, 0.6, ENTRYPOINT, 0.5);
        weights(ReviewDimension.TESTING, TEST, 0.9, TEST_CONFIG, 0.85, BUILD_MANIFEST, 0.5, CI, 0.5);
        weights(ReviewDimension.SECURITY, SECURITY, 0.95, MIDDLEWARE, 0.7, CONFIG, 0.7, ENV_TEMPLATE, 0.7,
                ROUTE_CONTROLLER, 0.6, CONTAINER, 0.5, BUILD_MANIFEST, 0.5);
        weights(ReviewDimension.PERSISTENCE, MIGRATION, 0.9, DATA_ACCESS, 0.9, MODEL, 0.85, DATABASE_CONFIG, 0.8, SCHEMA_DTO, 0.5,
                SERVICE, 0.5);
        weights(ReviewDimension.PERFORMANCE, DATA_ACCESS, 0.8, SERVICE, 0.75, ROUTE_CONTROLLER, 0.65, API_CLIENT, 0.6,
                MIDDLEWARE, 0.5, UTILITY, 0.5);
        weights(ReviewDimension.PRODUCTION_READINESS, CONTAINER, 0.95, CI, 0.95, DEPLOYMENT, 0.9, CONFIG, 0.85,
                ENV_TEMPLATE, 0.85, LOGGING, 0.85, BUILD_MANIFEST, 0.7, ENTRYPOINT, 0.6, DOCUMENTATION, 0.5);
    }

    /** Large source files get a boost for these dimensions: size is exactly what they examine. */
    static final double LARGE_FILE_BONUS = 0.15;

    private DimensionWeights() {
    }

    static double weight(ReviewDimension dimension, FileRole role) {
        return WEIGHTS.get(dimension).getOrDefault(role, 0.0);
    }

    static boolean boostsLargeFiles(ReviewDimension dimension) {
        return dimension == ReviewDimension.CODE_QUALITY || dimension == ReviewDimension.PERFORMANCE;
    }

    private static void weights(ReviewDimension dimension, Object... roleWeights) {
        Map<FileRole, Double> map = new EnumMap<>(FileRole.class);
        for (int i = 0; i < roleWeights.length; i += 2) {
            map.put((FileRole) roleWeights[i], (Double) roleWeights[i + 1]);
        }
        WEIGHTS.put(dimension, map);
    }
}
