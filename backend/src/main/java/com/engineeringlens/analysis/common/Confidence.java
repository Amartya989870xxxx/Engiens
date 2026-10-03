package com.engineeringlens.analysis.common;

/**
 * How strong the deterministic evidence is (not an AI score). HIGH: declared explicitly, e.g. a
 * dependency in a manifest. MEDIUM: implied by a well-known file or naming convention. LOW: weak hint.
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW
}
