package android.content.pm;
public final class PackageManager {public static final int PERMISSION_GRANTED=0,PERMISSION_DENIED=-1;public static final String FEATURE_TELEPHONY_MESSAGING="android.hardware.telephony.messaging";public boolean messaging=true;public boolean hasSystemFeature(String name){return messaging;} }
