package com.engineeringlens.scenario.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drains one output stream of a sandbox to the end, keeping at most {@code limit} bytes for display and
 * separating marked result lines. It always keeps reading (so the process never blocks on a full pipe),
 * but never keeps more than a bounded amount in memory, whatever the program prints.
 */
final class OutputCollector implements Runnable {

    private static final int MAX_LINE = 64 * 1024;
    private static final int MAX_RESULT_LINES = 500;

    private final InputStream in;
    private final int limit;
    private final String marker;
    private final ByteArrayOutputStream display = new ByteArrayOutputStream();
    private final List<String> results = Collections.synchronizedList(new ArrayList<>());
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean lineOverflow;
    private boolean pendingBlankLine;
    private volatile boolean truncated;

    /** @param marker result-line prefix, or null to treat everything as display output */
    OutputCollector(InputStream in, int limit, String marker) {
        this.in = in;
        this.limit = limit;
        this.marker = marker;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[8192];
        try (in) {
            for (int n; (n = in.read(buffer)) != -1;) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        endLine();
                    } else if (line.size() < MAX_LINE) {
                        line.write(buffer[i]);
                    } else {
                        lineOverflow = true;
                    }
                }
            }
            if (line.size() > 0) {
                endLine();
            }
        } catch (IOException e) {
            // The process was killed (timeout) or its stream closed: keep what was read.
        }
    }

    private void endLine() {
        String text = line.toString(StandardCharsets.UTF_8);
        boolean overflow = lineOverflow;
        line.reset();
        lineOverflow = false;
        if (marker != null && text.startsWith(marker)) {
            pendingBlankLine = false; // the runner writes "\n" before each result line; that blank line isn't user output
            if (results.size() < MAX_RESULT_LINES && !overflow) {
                results.add(text.substring(marker.length()));
            }
            return;
        }
        if (text.isEmpty()) {
            if (pendingBlankLine) {
                show("\n");
            }
            pendingBlankLine = true;
            return;
        }
        if (pendingBlankLine) {
            show("\n");
            pendingBlankLine = false;
        }
        show(text + (overflow ? " …" : "") + "\n");
    }

    private void show(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int room = limit - display.size();
        if (bytes.length <= room) {
            display.writeBytes(bytes);
        } else {
            if (room > 0) {
                display.write(bytes, 0, room);
            }
            truncated = true;
        }
    }

    String display() {
        return display.toString(StandardCharsets.UTF_8).stripTrailing();
    }

    List<String> results() {
        return List.copyOf(results);
    }

    boolean truncated() {
        return truncated;
    }
}
