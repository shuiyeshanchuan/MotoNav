package android.telephony;
import java.util.*;
public final class SubscriptionManager {public final List<SubscriptionInfo> active=new ArrayList<>(Arrays.asList(new SubscriptionInfo(7),new SubscriptionInfo(9)));public static boolean isValidSubscriptionId(int id){return id>=0;}public List<SubscriptionInfo> getActiveSubscriptionInfoList(){return active;} }
