package android.content;

import java.io.File;

/** Small runtime substitute for the paths used by the actual recorder class. */
public final class Context {
    private final File directory;
    public Context(File directory) { this.directory = directory; }
    public Context getApplicationContext() { return this; }
    public File getNoBackupFilesDir() { return new File(directory, "no-backup"); }
    public File getCacheDir() { return new File(directory, "cache"); }
}
