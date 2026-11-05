package android.util;
public final class Log {
    public static int e(String tag, String message, Throwable error) {
        System.out.println("Expected error path: " + tag + " " + message);
        return 0;
    }
}
