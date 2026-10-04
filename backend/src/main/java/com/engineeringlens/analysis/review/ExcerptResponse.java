package com.engineeringlens.analysis.review;

import java.util.List;

/** Lines of a file the review cites, read on demand from the pinned commit (never stored). */
public record ExcerptResponse(String file, int lineStart, int lineEnd, List<Line> lines) {

    public record Line(int number, String text) {
    }
}
