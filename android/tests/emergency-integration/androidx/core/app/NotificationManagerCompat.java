package androidx.core.app;
import android.app.Notification;
import android.content.Context;
public final class NotificationManagerCompat {private final Context context;private NotificationManagerCompat(Context c){context=c;}public static NotificationManagerCompat from(Context c){return new NotificationManagerCompat(c);}public boolean areNotificationsEnabled(){return context.notifications.enabled;}public void notify(int id,Notification n){context.notifications.latest=n;} }
