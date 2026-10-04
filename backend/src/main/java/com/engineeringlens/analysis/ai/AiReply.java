package com.engineeringlens.analysis.ai;

/**
 * One successful provider call. Token counts are null when the provider doesn't report them.
 * The text is model output: untrusted until validated.
 */
public record AiReply(String text, Integer inputTokens, Integer outputTokens) {

    @Override
    public String toString() {
        return "AiReply[chars=" + (text == null ? 0 : text.length()) + ", in=" + inputTokens + ", out=" + outputTokens + "]";
    }
}
