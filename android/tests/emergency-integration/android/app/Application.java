package android.app;
import java.io.File;
import android.content.Context;
import android.os.Bundle;
public class Application extends Context {
    public Application(File root){super(root);}public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callbacks){}
    public interface ActivityLifecycleCallbacks {void onActivityResumed(Activity a);void onActivityPaused(Activity a);void onActivityCreated(Activity a,Bundle b);void onActivityStarted(Activity a);void onActivityStopped(Activity a);void onActivitySaveInstanceState(Activity a,Bundle b);void onActivityDestroyed(Activity a);}
}
