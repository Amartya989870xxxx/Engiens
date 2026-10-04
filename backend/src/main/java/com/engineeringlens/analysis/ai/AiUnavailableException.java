package com.engineeringlens.analysis.ai;

/**
 * No model produced a valid answer. code distinguishes "nothing configured", "every model unavailable"
 * and "the request itself was rejected" so callers can show the right message.
 */
public class AiUnavailableException extends RuntimeException {

    public static final String NOT_CONFIGURED = "AI_NOT_CONFIGURED";
    public static final String UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String REQUEST_REJECTED = "AI_REQUEST_REJECTED";

    private final String code;

    public AiUnavailableException(String code, String sanitizedMessage) {
        super(sanitizedMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
