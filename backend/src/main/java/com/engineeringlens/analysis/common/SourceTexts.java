package com.engineeringlens.analysis.common;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * File contents fetched during one analysis run, plus why any requested file couldn't be read.
 * Sorted by path so everything computed from it is reproducible.
 */
public final class SourceTexts {

    private final Map<String, String> texts = new TreeMap<>();
    private final Map<String, String> unread = new TreeMap<>();

    public void put(String path, String text) {
        texts.put(path, text);
    }

    public void markUnread(String path, String reason) {
        unread.put(path, reason);
    }

    public String get(String path) {
        return texts.get(path);
    }

    public boolean has(String path) {
        return texts.containsKey(path);
    }

    public boolean attempted(String path) {
        return texts.containsKey(path) || unread.containsKey(path);
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(texts);
    }

    public Map<String, String> unread() {
        return Collections.unmodifiableMap(unread);
    }

    public static SourceTexts of(Map<String, String> texts) {
        SourceTexts s = new SourceTexts();
        texts.forEach(s::put);
        return s;
    }
}
