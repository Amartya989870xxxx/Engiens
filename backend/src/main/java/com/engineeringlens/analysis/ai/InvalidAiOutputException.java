package com.engineeringlens.analysis.ai;

/** The model answered, but its output failed validation. Says what was wrong, never echoes the output. */
public class InvalidAiOutputException extends RuntimeException {

    public InvalidAiOutputException(String reason) {
        super(reason);
    }
}
