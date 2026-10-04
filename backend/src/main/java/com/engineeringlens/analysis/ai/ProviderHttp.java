package com.engineeringlens.analysis.ai;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** HTTP plumbing shared by provider adapters: timeouts, network-failure classification, safe messages. */
final class ProviderHttp {

    private ProviderHttp() {
    }

    static RestClient.Builder builder(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Generous: a full review with deep thinking can legitimately take minutes.
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
    }

    static AiProviderException networkFailure(ResourceAccessException e) {
        return e.getCause() instanceof SocketTimeoutException
                ? new AiProviderException(AiFailureType.TIMEOUT, "The provider did not respond in time")
                : new AiProviderException(AiFailureType.NETWORK, "Could not reach the provider");
    }

    private static final Pattern KEYS = Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}|gsk_[0-9A-Za-z]{20,}|Bearer\\s+\\S+");

    /** A short, single-line message with anything key-like removed: safe to log and to store. */
    static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String oneLine = KEYS.matcher(message).replaceAll("[redacted]").replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "…";
    }

    private static final Pattern SECONDS = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)s");

    /** Parses "37s" / "1.5s" (Gemini RetryInfo) or "37" (Retry-After header, seconds). */
    static Duration parseDelay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        Matcher m = SECONDS.matcher(v);
        try {
            double seconds = m.matches() ? Double.parseDouble(m.group(1)) : Double.parseDouble(v);
            return Duration.ofMillis((long) (seconds * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
