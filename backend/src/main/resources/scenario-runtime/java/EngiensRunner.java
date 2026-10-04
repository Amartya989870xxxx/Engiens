import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the hidden checks against the user's workspace and reports each result on its own marked line.
 * Owned by Engiens. The marker is read from a file that is deleted before any user class is loaded.
 */
public final class EngiensRunner {

    private static final long CHECK_TIMEOUT_MS = 5000;
    private static String marker;
    private static PrintStream out;

    public static void main(String[] args) throws Exception {
        Path noncePath = Path.of(".engiens_nonce");
        marker = "@@ENGIENS:" + Files.readString(noncePath).strip() + ":";
        Files.delete(noncePath);
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

        Engiens engiens = new Engiens();
        try {
            Class.forName("EngiensChecks").getMethod("register", Engiens.class).invoke(null, engiens);
        } catch (InvocationTargetException e) {
            emit("{\"kind\":\"summary\",\"outcome\":\"load_error\",\"message\":" + json(describe(e.getCause())) + "}");
            System.exit(0);
        } catch (Throwable e) {
            emit("{\"kind\":\"summary\",\"outcome\":\"load_error\",\"message\":" + json(describe(e)) + "}");
            System.exit(0);
        }

        for (Engiens.Registered c : engiens.checks) {
            long start = System.nanoTime();
            Throwable[] failure = { null };
            Thread t = new Thread(() -> {
                try {
                    c.check().run();
                } catch (Throwable e) {
                    failure[0] = e;
                }
            }, "engiens-check");
            t.setDaemon(true);
            t.start();
            t.join(CHECK_TIMEOUT_MS);
            String message = null;
            if (t.isAlive()) {
                message = "Took longer than " + CHECK_TIMEOUT_MS / 1000 + " s";
            } else if (failure[0] instanceof AssertionError a) {
                message = a.getMessage() == null ? "A check assertion failed" : a.getMessage();
            } else if (failure[0] != null) {
                message = describe(failure[0]);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            emit("{\"kind\":\"check\",\"name\":" + json(c.name()) + ",\"passed\":" + (message == null) + ",\"message\":"
                    + (message == null ? "null" : json(message)) + ",\"ms\":" + ms + "}");
        }
        emit("{\"kind\":\"summary\",\"outcome\":\"ran\"}");
        System.exit(0); // don't wait for threads user code (or a timed-out check) left running
    }

    /** Type, message and the last frame in the user's own classes. Engiens classes are never shown. */
    private static String describe(Throwable e) {
        Throwable root = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
        StringBuilder text = new StringBuilder(root.getClass().getSimpleName());
        if (root.getMessage() != null) {
            text.append(": ").append(root.getMessage());
        }
        for (StackTraceElement frame : root.getStackTrace()) {
            if (frame.getFileName() != null && !frame.getClassName().startsWith("Engiens") && !frame.getClassName().startsWith("java.")
                    && !frame.getClassName().startsWith("jdk.")) {
                text.append(" (at ").append(frame.getFileName()).append(" line ").append(frame.getLineNumber()).append(')');
                break;
            }
        }
        return text.length() <= 1000 ? text.toString() : text.substring(0, 1000);
    }

    private static void emit(String json) {
        out.print("\n" + marker + json + "\n");
        out.flush();
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
