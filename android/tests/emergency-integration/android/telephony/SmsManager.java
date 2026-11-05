package android.telephony;
import android.app.PendingIntent;
import java.util.*;
public final class SmsManager {
    public static final class Submission {public final int subscriptionId;public final String phone;public final ArrayList<String> parts;public final ArrayList<PendingIntent> sent,delivered;Submission(int id,String phone,ArrayList<String> p,ArrayList<PendingIntent>s,ArrayList<PendingIntent>d){subscriptionId=id;this.phone=phone;parts=new ArrayList<>(p);sent=s;delivered=d;}}
    public static final ArrayList<Submission> submissions=new ArrayList<>();public static boolean throwOnSend;public static int segmentLength=70;public static Runnable beforeSubmit;
    private final int id;private SmsManager(int id){this.id=id;}public static SmsManager getSmsManagerForSubscriptionId(int id){return new SmsManager(id);}
    public ArrayList<String> divideMessage(String body){ArrayList<String> out=new ArrayList<>();for(int i=0;i<body.length();i+=segmentLength)out.add(body.substring(i,Math.min(body.length(),i+segmentLength)));return out;}
    public void sendMultipartTextMessage(String phone,String center,ArrayList<String> parts,ArrayList<PendingIntent> sent,ArrayList<PendingIntent> delivered){if(beforeSubmit!=null)beforeSubmit.run();if(throwOnSend)throw new IllegalStateException("simulated modem rejection");submissions.add(new Submission(id,phone,parts,sent,delivered));}
}
