package android.app;
import java.util.ArrayList;
public class Notification {public static final int VISIBILITY_PRIVATE=0;public String title,text,detail;public final ArrayList<PendingIntent> actions=new ArrayList<>();public boolean ongoing;}
