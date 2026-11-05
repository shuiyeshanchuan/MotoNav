package android.content;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionManager;
public class Context {
    private final File root;public final PackageManager packages=new PackageManager();public final NotificationManager notifications=new NotificationManager();public final SubscriptionManager subscriptions=new SubscriptionManager();
    public final Map<String,Integer> permissions=new HashMap<>();
    public Context(){this(new File("unused"));}public Context(File root){this.root=root;permissions.put(android.Manifest.permission.SEND_SMS,0);permissions.put(android.Manifest.permission.READ_PHONE_STATE,0);}
    public Context getApplicationContext(){return this;}public File getNoBackupFilesDir(){return new File(root,"no-backup");}public File getCacheDir(){return new File(root,"cache");}
    public PackageManager getPackageManager(){return packages;}public int checkSelfPermission(String p){return permissions.getOrDefault(p,0);}
    public <T>T getSystemService(Class<T> type){if(type==NotificationManager.class)return type.cast(notifications);if(type==SubscriptionManager.class)return type.cast(subscriptions);throw new AssertionError(type);}
    public void startActivity(Intent intent){}
}
