package com.engineeringlens.analysis.ai;

/**
 * A model provider (Gemini, Groq, ...). Implementations translate the neutral prompt and settings into
 * the provider's API and classify failures as {@link AiProviderException}s. They hold no routing logic:
 * no retries, no fallback, no health tracking. That is the router's job.
 */
public interface AiProvider {

    /** Stable provider id used in configuration and records, e.g. "gemini". */
    String id();

    /** True when credentials are present. An unconfigured provider is skipped, never an error at startup. */
    boolean configured();

    AiReply generate(String model, AiPrompt prompt, AiGenerationSettings settings);
}
