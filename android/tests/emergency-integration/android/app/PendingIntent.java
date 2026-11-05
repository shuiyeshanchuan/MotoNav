package android.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT=0x08000000,FLAG_IMMUTABLE=0x04000000,FLAG_MUTABLE=0x02000000;
    public final Intent intent;public final int flags;private final Context context;
    private PendingIntent(Context c,Intent i,int flags){context=c;intent=i;this.flags=flags;}
    public static PendingIntent getBroadcast(Context c,int request,Intent i,int flags){return new PendingIntent(c,i,flags);}public static PendingIntent getActivity(Context c,int request,Intent i,int flags){return new PendingIntent(c,i,flags);}
    public void dispatch(int result){dispatch(result,null,null);}
    public void dispatch(int result,byte[] pdu,String format){try{if(pdu!=null&&(flags&FLAG_MUTABLE)!=0){intent.putExtra("pdu",pdu);intent.putExtra("format",format);}BroadcastReceiver receiver=(BroadcastReceiver)intent.target.getDeclaredConstructor().newInstance();receiver.setResultCode(result);receiver.onReceive(context,intent);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
}
