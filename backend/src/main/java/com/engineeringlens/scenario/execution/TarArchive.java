package com.engineeringlens.scenario.execution;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;

/**
 * A minimal POSIX "ustar" archive writer. The sandbox receives its files as a tar stream on stdin, so no
 * host folder is ever mounted into a container. Paths are pre-validated (plain ASCII, relative), so the
 * format's corner cases (long names beyond 255 bytes, links, special files) are never needed.
 */
final class TarArchive {

    private static final int BLOCK = 512;

    private TarArchive() {
    }

    static byte[] of(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TreeSet<String> directories = new TreeSet<>();
        for (String path : files.keySet()) {
            for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) {
                directories.add(path.substring(0, i + 1));
            }
        }
        for (String dir : directories) {
            header(out, dir, 0, '5', "0000755");
        }
        files.forEach((path, text) -> {
            byte[] content = text.getBytes(StandardCharsets.UTF_8);
            header(out, path, content.length, '0', "0000644");
            out.writeBytes(content);
            out.writeBytes(new byte[(BLOCK - content.length % BLOCK) % BLOCK]);
        });
        out.writeBytes(new byte[BLOCK * 2]); // end of archive
        return out.toByteArray();
    }

    private static void header(ByteArrayOutputStream out, String path, long size, char type, String mode) {
        byte[] h = new byte[BLOCK];
        byte[] name = path.getBytes(StandardCharsets.US_ASCII);
        if (name.length <= 100) {
            put(h, 0, name);
        } else {
            // ustar splits long paths into prefix (155) + "/" + name (100).
            int cut = path.lastIndexOf('/', 155);
            if (cut <= 0 || name.length - cut - 1 > 100) {
                throw new IllegalArgumentException("Path too long for the sandbox: " + path);
            }
            put(h, 345, path.substring(0, cut).getBytes(StandardCharsets.US_ASCII));
            put(h, 0, path.substring(cut + 1).getBytes(StandardCharsets.US_ASCII));
        }
        put(h, 100, (mode + "\0").getBytes(StandardCharsets.US_ASCII));
        put(h, 108, "0000000\0".getBytes(StandardCharsets.US_ASCII)); // uid
        put(h, 116, "0000000\0".getBytes(StandardCharsets.US_ASCII)); // gid
        put(h, 124, String.format("%011o\0", size).getBytes(StandardCharsets.US_ASCII));
        put(h, 136, "00000000000\0".getBytes(StandardCharsets.US_ASCII)); // mtime
        put(h, 148, "        ".getBytes(StandardCharsets.US_ASCII)); // checksum is computed with spaces here
        h[156] = (byte) type;
        put(h, 257, "ustar\0".getBytes(StandardCharsets.US_ASCII));
        put(h, 263, "00".getBytes(StandardCharsets.US_ASCII));
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(h);
    }

    private static void put(byte[] header, int offset, byte[] value) {
        System.arraycopy(value, 0, header, offset, value.length);
    }
}
