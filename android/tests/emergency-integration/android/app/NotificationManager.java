package android.app;
import java.util.HashMap;
public final class NotificationManager {public static final int IMPORTANCE_NONE=0,IMPORTANCE_HIGH=4;private final HashMap<String,NotificationChannel> channels=new HashMap<>();public boolean enabled=true;public Notification latest;public NotificationChannel getNotificationChannel(String id){return channels.get(id);}public void createNotificationChannel(NotificationChannel channel){channels.putIfAbsent(channel.id,channel);}public boolean canUseFullScreenIntent(){return false;} }
