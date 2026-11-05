package android.os;
/** Execute posts immediately so protocol/file completion remains observable. */
public final class Handler {
    public Handler(Looper looper) {}
    public boolean post(Runnable runnable) { runnable.run(); return true; }
}
