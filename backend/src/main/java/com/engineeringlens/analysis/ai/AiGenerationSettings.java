package com.engineeringlens.analysis.ai;

/**
 * Generic generation settings. Adapters translate them: e.g. Gemini's thinkingLevel and
 * responseMimeType, Groq's reasoning_effort and response_format.
 *
 * @param thinking   requested reasoning depth: "low", "medium" or "high"
 * @param jsonOutput ask the provider for strict JSON
 */
public record AiGenerationSettings(double temperature, int maxOutputTokens, String thinking, boolean jsonOutput) {
}
