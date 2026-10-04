import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Engiens check API. Owned by Engiens, never generated: hidden checks call engiens.check(name, () -> ...). */
public final class Engiens {

    @FunctionalInterface
    public interface Check {
        void run() throws Throwable;
    }

    record Registered(String name, Check check) {
    }

    final List<Registered> checks = new ArrayList<>();

    public void check(String name, Check check) {
        checks.add(new Registered(name, check));
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, Check body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError(message + " (threw " + t.getClass().getSimpleName() + " instead)");
        }
        throw new AssertionError(message);
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }
}
