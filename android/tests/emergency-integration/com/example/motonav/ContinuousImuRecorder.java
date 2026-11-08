package com.example.motonav;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import java.util.*;
public final class ContinuousImuRecorder {
    public static final ContinuousImuRecorder INSTANCE=new ContinuousImuRecorder();
    public Function2<? super ContinuousImuProtocol.Batch,? super Long,Unit> observer;
    public void setLiveBatchObserver(Function2<? super ContinuousImuProtocol.Batch,? super Long,Unit> value){observer=value;}
    public static final class Event {
        public final String kind;public final long token,at;public final Map<String,String> fields;
        Event(String kind,long token,Map<String,String> fields){this.kind=kind;this.token=token;this.at=android.os.SystemClock.now;this.fields=new LinkedHashMap<>(fields);}
    }
    public static final List<Event> journal=new ArrayList<>();
    public static final Map<String,String> configuration=new LinkedHashMap<>();
    public static boolean failJournal;
    public void recordEmergencyEvent(String kind,long token,Map<String,String> fields){if(failJournal)throw new IllegalStateException("test-only journal failure");journal.add(new Event(kind,token,fields));}
    public void recordDetectorConfiguration(Map<String,String> config){configuration.clear();configuration.putAll(config);}
}
