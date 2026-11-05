package com.example.motonav;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
public final class ContinuousImuRecorder {public static final ContinuousImuRecorder INSTANCE=new ContinuousImuRecorder();public Function2<? super ContinuousImuProtocol.Batch,? super Long,Unit> observer;public void setLiveBatchObserver(Function2<? super ContinuousImuProtocol.Batch,? super Long,Unit> value){observer=value;} }
