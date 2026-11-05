package androidx.core.app;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
public final class NotificationCompat {
    public static final String CATEGORY_ALARM="alarm";public static final int PRIORITY_MAX=2,VISIBILITY_PRIVATE=0;
    public abstract static class Style { String text; }
    public static final class BigTextStyle extends Style {public BigTextStyle bigText(CharSequence v){text=v.toString();return this;}}
    public static final class Builder {
        private final Notification n=new Notification();public Builder(Context c,String channel){}
        public Builder setSmallIcon(int v){return this;}public Builder setContentTitle(CharSequence v){n.title=v.toString();return this;}public Builder setContentText(CharSequence v){n.text=v.toString();return this;}public Builder setStyle(Style v){n.detail=v.text;return this;}
        public Builder setContentIntent(PendingIntent p){return this;}public Builder setCategory(String v){return this;}public Builder setPriority(int v){return this;}public Builder setVisibility(int v){return this;}public Builder setOnlyAlertOnce(boolean v){return this;}public Builder setOngoing(boolean v){n.ongoing=v;return this;}public Builder addAction(int icon,CharSequence text,PendingIntent p){n.actions.add(p);return this;}public Builder setFullScreenIntent(PendingIntent p,boolean v){return this;}public Notification build(){return n;}
    }
}
