package android.content;
public abstract class BroadcastReceiver {private int resultCode;public int getResultCode(){return resultCode;}public void setResultCode(int value){resultCode=value;}public abstract void onReceive(Context context,Intent intent);}
