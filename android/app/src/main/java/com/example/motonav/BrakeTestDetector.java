package com.example.motonav;

import java.util.ArrayDeque;

/**
 * Opt-in harsh-braking TEST trigger, never an accident verdict. Both live board IMU
 * and real, fresh GPS speed loss are required. All times are phone elapsed-realtime
 * milliseconds; callers must map board acquisition time before calling onImu and
 * explicitly mark reconnect/backfill samples live=false. No raw gyro bias is changed.
 */
public final class BrakeTestDetector {
    public static final double DYNAMIC_THRESHOLD_MPS2=2.5;
    public static final long DYNAMIC_DURATION_MS=120, MAX_GPS_WINDOW_MS=2500;
    public static final long MAX_IMU_AGE_MS=500, MAX_GPS_AGE_MS=1500, EVIDENCE_WINDOW_MS=2000;
    private static final long WARMUP_MS=1000, MAX_IMU_GAP_MS=200;
    private static final double GRAVITY_TIME_CONSTANT_MS=1500;
    public enum State { DISARMED, WARMING_UP, READY, TRIGGERED }

    public static final class Trigger {
        public final long detectedAtMs, imuAtMs, gpsAtMs;
        public final double dynamicAccelerationMps2, initialSpeedKmh, finalSpeedKmh, speedDropKmh, decelerationMps2;
        private Trigger(long now,long imuAt,long gpsAt,double dynamic,double initial,double current,double deceleration) {
            detectedAtMs=now;imuAtMs=imuAt;gpsAtMs=gpsAt;dynamicAccelerationMps2=dynamic;
            initialSpeedKmh=initial;finalSpeedKmh=current;speedDropKmh=initial-current;decelerationMps2=deceleration;
        }
    }
    private static final class Fix {
        final long at;final double speed;
        Fix(long at,double speed) { this.at=at;this.speed=speed; }
    }
    private final ArrayDeque<Fix> fixes=new ArrayDeque<>();
    private State state=State.DISARMED;
    private long armedAt,lastImu=-1,gravitySince=-1,dynamicSince=-1,imuEvidenceAt=-1,gpsEvidenceAt=-1,lastGps=-1;
    private double gravityX,gravityY,gravityZ,dynamicAcceleration,imuEvidencePeak,runPeak;
    private double initialSpeed,finalSpeed,deceleration;
    private String rejection="not_armed";

    /** A manual arm operation. Simulation, cruise and absence of navigation must pass false. */
    public synchronized boolean arm(long nowMs,boolean navigating,boolean simulation) {
        clearEvidence();
        if(nowMs<0 || !navigating || simulation) { state=State.DISARMED;rejection="real_navigation_required";return false; }
        armedAt=nowMs;state=State.WARMING_UP;rejection="gravity_warmup";return true;
    }
    public synchronized void disarm() { clearEvidence();state=State.DISARMED;rejection="not_armed"; }
    /** Clear every cross-link/time association; no auto-arm after a trigger. */
    public synchronized void onLinkInterrupted() {
        clearEvidence();if(isArmed()) { state=State.WARMING_UP;rejection="link_interrupted"; }
    }
    public synchronized boolean isArmed() { return state==State.WARMING_UP || state==State.READY; }
    public synchronized State state() { return state; }
    public synchronized String status() { return rejection; }
    public synchronized double lastDynamicAccelerationMps2() { return dynamicAcceleration; }
    /** An invalid/missing location callback must not leave a former braking pair eligible. */
    public synchronized void invalidateGps() { clearGps();rejection="gps_not_fresh_or_accurate"; }

    public synchronized Trigger onImu(long acquiredMs,long receivedMs,double ax,double ay,double az,
                                       double gx,double gy,double gz,boolean live,boolean healthy) {
        if(!isArmed())return null;
        if(!live || !healthy || !fresh(acquiredMs,receivedMs,MAX_IMU_AGE_MS) || acquiredMs<armedAt ||
                !finite(ax,ay,az,gx,gy,gz) || magnitude(ax,ay,az)>200 || magnitude(gx,gy,gz)>2000) {
            clearEvidence();state=State.WARMING_UP;rejection="imu_not_live_or_healthy";return null;
        }
        if(lastImu>=0 && (acquiredMs<=lastImu || acquiredMs-lastImu>MAX_IMU_GAP_MS)) {
            clearEvidence();state=State.WARMING_UP;rejection="imu_time_discontinuity";
        }
        long previous=lastImu;lastImu=acquiredMs;
        if(gravitySince<0) {
            double norm=magnitude(ax,ay,az);
            if(norm<6 || norm>14 || magnitude(gx,gy,gz)>30) { rejection="gravity_warmup";return null; }
            gravityX=ax;gravityY=ay;gravityZ=az;gravitySince=acquiredMs;rejection="gravity_warmup";return null;
        }
        double dx=ax-gravityX,dy=ay-gravityY,dz=az-gravityZ;
        dynamicAcceleration=magnitude(dx,dy,dz);
        long dt=previous<0?0:acquiredMs-previous;
        double alpha=dt/(GRAVITY_TIME_CONSTANT_MS+dt);
        gravityX+=alpha*dx;gravityY+=alpha*dy;gravityZ+=alpha*dz;
        if(acquiredMs-gravitySince<WARMUP_MS) { dynamicSince=-1;runPeak=0;rejection="gravity_warmup";return null; }
        state=State.READY;
        if(dynamicAcceleration>=DYNAMIC_THRESHOLD_MPS2) {
            if(dynamicSince<0) { dynamicSince=acquiredMs;runPeak=0; }
            runPeak=Math.max(runPeak,dynamicAcceleration);
            if(acquiredMs-dynamicSince>=DYNAMIC_DURATION_MS) { imuEvidenceAt=acquiredMs;imuEvidencePeak=runPeak; }
        } else { dynamicSince=-1;runPeak=0; }
        rejection=imuEvidenceAt>=0?"waiting_for_gps_braking":"waiting_for_braking";
        return triggerIfReady(receivedMs);
    }

    public synchronized Trigger onGps(long fixMs,long receivedMs,double speedKmh,double accuracyM,boolean realFix) {
        if(!isArmed())return null;
        if(!realFix || !fresh(fixMs,receivedMs,MAX_GPS_AGE_MS) || fixMs<armedAt ||
                !Double.isFinite(speedKmh) || speedKmh<0 || speedKmh>250 ||
                !Double.isFinite(accuracyM) || accuracyM<=0 || accuracyM>30 || lastGps>=0 && fixMs<=lastGps) {
            clearGps();rejection="gps_not_fresh_or_accurate";return null;
        }
        lastGps=fixMs;
        while(!fixes.isEmpty() && fixMs-fixes.peekFirst().at>MAX_GPS_WINDOW_MS)fixes.removeFirst();
        for(Fix earlier:fixes) {
            long span=fixMs-earlier.at;
            if(span<120 || earlier.speed<12)continue;
            double drop=earlier.speed-speedKmh, decel=drop/3.6/(span/1000.0);
            if(drop>=8 && decel>=2.5) {
                gpsEvidenceAt=fixMs;initialSpeed=earlier.speed;finalSpeed=speedKmh;deceleration=decel;break;
            }
        }
        if(fixes.size()==64)fixes.removeFirst();
        fixes.addLast(new Fix(fixMs,speedKmh));
        return triggerIfReady(receivedMs);
    }
    private Trigger triggerIfReady(long now) {
        if(imuEvidenceAt<0 || gpsEvidenceAt<0 || Math.abs(imuEvidenceAt-gpsEvidenceAt)>EVIDENCE_WINDOW_MS ||
                now<imuEvidenceAt || now<gpsEvidenceAt || now-imuEvidenceAt>EVIDENCE_WINDOW_MS || now-gpsEvidenceAt>MAX_GPS_AGE_MS)return null;
        Trigger result=new Trigger(now,imuEvidenceAt,gpsEvidenceAt,imuEvidencePeak,initialSpeed,finalSpeed,deceleration);
        state=State.TRIGGERED;rejection="triggered_manual_rearm_required";clearEvidence();return result;
    }
    private void clearEvidence() {
        lastImu=gravitySince=dynamicSince=imuEvidenceAt=-1;gravityX=gravityY=gravityZ=dynamicAcceleration=imuEvidencePeak=runPeak=0;clearGps();
    }
    private void clearGps() { fixes.clear();lastGps=gpsEvidenceAt=-1;initialSpeed=finalSpeed=deceleration=0; }
    private static boolean fresh(long acquired,long received,long maximumAge) { return acquired>=0 && received>=acquired && received-acquired<=maximumAge; }
    private static boolean finite(double... values) { for(double value:values)if(!Double.isFinite(value))return false;return true; }
    private static double magnitude(double x,double y,double z) { return Math.sqrt(x*x+y*y+z*z); }
}
