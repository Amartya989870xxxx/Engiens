package com.engineeringlens.analysis.ai;

/**
 * A provider-neutral prompt: instructions (system role) and content (user turn). Each provider adapter
 * maps these to its own request format. Never logged: it contains the developer's source code.
 */
public record AiPrompt(String system, String user) {

    @Override
    public String toString() {
        return "AiPrompt[systemChars=" + system.length() + ", userChars=" + user.length() + "]"; // never the content
    }
}
