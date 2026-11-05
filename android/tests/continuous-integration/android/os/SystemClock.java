package android.os;

import java.util.concurrent.atomic.AtomicLong;

public final class SystemClock {
    private static final long START = System.nanoTime();
    private static final AtomicLong OFFSET = new AtomicLong(10000);
    public static long elapsedRealtime() { return OFFSET.get() + (System.nanoTime() - START) / 1000000; }
    public static void advance(long millis) { OFFSET.addAndGet(millis); }
}
