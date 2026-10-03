package com.engineeringlens.common;

import java.util.Map;

/** Single error shape returned by every failing API call. */
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, Map.of());
    }
}
