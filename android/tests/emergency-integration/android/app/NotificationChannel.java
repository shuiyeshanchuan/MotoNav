package android.app;
import android.net.Uri;
import android.media.AudioAttributes;
public final class NotificationChannel {public final String id;private int importance;public NotificationChannel(String id,CharSequence name,int importance){this.id=id;this.importance=importance;}public int getImportance(){return importance;}public void setImportance(int v){importance=v;}public void setDescription(String value){}public void enableVibration(boolean v){}public void setSound(Uri uri,AudioAttributes attributes){}public void setLockscreenVisibility(int v){} }
