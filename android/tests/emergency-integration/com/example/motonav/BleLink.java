package com.example.motonav;
import java.util.*;
public final class BleLink {
    public static final BleLink INSTANCE=new BleLink();public static boolean connected=true,verified=true;
    public static final ArrayList<String> events=new ArrayList<>();
    public static final class Image {public final int w,h;public final byte[] bytes;Image(int w,int h,byte[] data){this.w=w;this.h=h;bytes=data.clone();}}
    public static final Map<Integer,Image> retainedImages=new HashMap<>(),visibleImages=new HashMap<>();
    public boolean isConnected(){return connected;}public boolean getVerified(){return verified;}public boolean getSupportsEmergencyAlarm(){return true;}public boolean getSupportsContinuousImu(){return true;}
    public void sendEvent(String value){events.add(value);}
    public void sendImage(int slot,int w,int h,byte[] bytes){Image image=new Image(w,h,bytes);retainedImages.put(slot,image);if(connected)emit(slot,image);}
    private static void emit(int slot,Image image){if(image.w==0)visibleImages.remove(slot);else visibleImages.put(slot,image);}
    public static void resendRetainedImages(){if(connected)retainedImages.forEach(BleLink::emit);}
}
