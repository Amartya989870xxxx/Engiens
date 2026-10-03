package com.engineeringlens.analysis.source;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Turns fetched bytes into text, refusing anything that isn't clean UTF-8 text rather than guessing. */
public final class SourceDecoder {

    private SourceDecoder() {
    }

    /** Either text or the reason it couldn't be used. */
    public record Decoded(String text, String skipReason) {
    }

    public static Decoded decode(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++) {
            if (bytes[i] == 0) {
                return new Decoded(null, "Binary content");
            }
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new Decoded(text.startsWith("﻿") ? text.substring(1) : text, null);
        } catch (CharacterCodingException e) {
            return new Decoded(null, "Not valid UTF-8 text");
        }
    }
}
