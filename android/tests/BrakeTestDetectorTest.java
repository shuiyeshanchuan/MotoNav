package com.example.motonav;

/** Synthetic causal traces; these checks do not claim real-device crash/braking accuracy. */
public final class BrakeTestDetectorTest {
    private static int checks;
    private static void check(boolean pass,String message) { checks++;if(!pass)throw new AssertionError(message); }
    private static BrakeTestDetector detector() { BrakeTestDetector d=new BrakeTestDetector();check(d.arm(0,true,false),"arm explicit real navigation");return d; }
    private static BrakeTestDetector.Trigger imu(BrakeTestDetector d,long at,double dynamic) {
        return d.onImu(at,at,-dynamic,0,9.068,-7,0.6,0.1,true,true);
    }
    private static void rest(BrakeTestDetector d,long from,long to) { for(long at=from;at<=to;at+=40)check(imu(d,at,0)==null,"rest must not trigger"); }
    private static void brake(BrakeTestDetector d,long from,long to) { for(long at=from;at<=to;at+=40)check(imu(d,at,4)==null,"IMU alone cannot trigger"); }
    private static void orientation() {
        // A rotation preserves gravity/dynamic lengths; cover axes and an oblique mount.
        double[][] normals={{0,0,1},{0,0,-1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0.36,0.48,0.8}};
        for(double[] n:normals) {
            double px=-n[1],py=n[0],pz=0;
            double length=Math.sqrt(px*px+py*py+pz*pz);
            if(length<0.1) { px=1;py=0;pz=0;length=1; }
            px/=length;py/=length;pz/=length;
            BrakeTestDetector d=detector();
            for(int t=0;t<=1200;t+=40)check(d.onImu(t,t,9.068*n[0],9.068*n[1],9.068*n[2],-7,0.6,0.1,true,true)==null,"mount warmup");
            check(d.state()==BrakeTestDetector.State.READY,"mount independent gravity warmup");
            d.onGps(1000,1000,36,5,true);
            for(int t=1240;t<=1440;t+=40)d.onImu(t,t,9.068*n[0]+4*px,9.068*n[1]+4*py,9.068*n[2]+4*pz,-7,0.6,0.1,true,true);
            BrakeTestDetector.Trigger trigger=d.onGps(1440,1440,20,5,true);
            check(trigger!=null,"dual braking evidence at arbitrary mount");
            check(trigger.dynamicAccelerationMps2>=2.5 && trigger.speedDropKmh==16 && trigger.decelerationMps2>=2.5,"quantitative evidence retained");
            check(!d.isArmed() && d.state()==BrakeTestDetector.State.TRIGGERED,"trigger disarms until manual rearm");
            check(d.onGps(1500,1500,0,5,true)==null && imu(d,1500,5)==null,"single trigger only");
        }
    }
    private static void negatives() {
        BrakeTestDetector d=new BrakeTestDetector();
        check(!d.arm(0,false,false) && !d.arm(0,true,true),"navigation and simulation gates");
        check(d.onGps(0,0,40,5,true)==null && imu(d,0,5)==null,"disarmed inputs inert");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1600);
        check(d.onGps(1600,1600,36,5,true)==null,"shaking/bump without speed loss cannot trigger");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);rest(d,1240,1600);
        check(d.onGps(1600,1600,0,5,true)==null,"GPS speed change alone cannot trigger");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);imu(d,1240,8);imu(d,1280,8);rest(d,1320,1440);
        check(d.onGps(1440,1440,0,5,true)==null,"short road bump below sustained duration rejected");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,10,5,true);brake(d,1240,1440);
        check(d.onGps(1440,1440,0,5,true)==null,"below minimum initial speed");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        check(d.onGps(1440,1440,29,5,true)==null,"speed loss below eight kmh");
        d=detector();rest(d,0,2800);d.onGps(1000,1000,36,5,true);brake(d,2840,3040);
        check(d.onGps(3040,3040,27,5,true)==null,"slow GPS deceleration rejected");
        d=detector();rest(d,0,3800);d.onGps(1000,1000,36,5,true);brake(d,3840,4040);
        check(d.onGps(4040,4040,0,5,true)==null,"speed history older than 2.5 seconds expires");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        check(d.onGps(1440,1440,0,31,true)==null,"poor location accuracy rejected");
        check(d.onGps(1480,1480,0,5,true)==null,"invalid fix clears earlier speed evidence");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        check(d.onGps(1440,3000,0,5,true)==null,"stale GPS fix rejected");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        check(d.onGps(1440,1440,0,5,false)==null,"non-real GPS rejected");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);
        for(long t=1240;t<=1480;t+=40)d.onImu(t,t,-5,0,9.068,-7,0.6,0.1,false,true);
        check(d.onGps(1480,1480,0,5,true)==null,"reconnect backfill never creates braking evidence");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);
        for(long t=1240;t<=1480;t+=40)d.onImu(t,t+501,-5,0,9.068,-7,0.6,0.1,true,true);
        check(d.onGps(1480,2000,0,5,true)==null,"aged IMU rejected despite live label");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);d.onLinkInterrupted();
        check(d.isArmed() && d.state()==BrakeTestDetector.State.WARMING_UP,"link loss resets warmup without auto-rearm");
        check(d.onGps(1480,1480,0,5,true)==null,"pre-disconnect evidence is cleared");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        d.onGps(3600,3600,36,5,true);
        check(d.onGps(4000,4000,0,5,true)==null,"separated IMU/GPS evidence rejected");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);d.onGps(1240,1240,20,5,true);
        rest(d,1240,2680);
        for(long t=2720;t<=2920;t+=40)check(imu(d,t,4)==null,"GPS evidence older than 1500ms never triggers on later IMU");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);d.onGps(1240,1240,20,5,true);d.invalidateGps();
        brake(d,1240,1480);check(d.isArmed(),"invalid fix explicitly clears preceding GPS candidate");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        d.onImu(1400,1480,-5,0,9.068,-7,0.6,0.1,true,true);
        check(d.onGps(1480,1480,0,5,true)==null,"out of order IMU breaks association");
        d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);brake(d,1240,1440);
        d.onImu(1480,1480,Double.NaN,0,9.068,-7,0.6,0.1,true,true);
        check(d.onGps(1480,1480,0,5,true)==null,"nonfinite raw sample invalidates evidence");
        d.disarm();check(!d.isArmed(),"navigation end explicitly disarms");
    }
    private static void gpsFirst() {
        BrakeTestDetector d=detector();rest(d,0,1200);d.onGps(1000,1000,36,5,true);
        check(d.onGps(1240,1240,20,5,true)==null,"GPS first waits for IMU");
        BrakeTestDetector.Trigger trigger=null;
        for(long t=1240;t<=1480;t+=40) { BrakeTestDetector.Trigger result=imu(d,t,4);if(result!=null)trigger=result; }
        check(trigger!=null && trigger.gpsAtMs==1240,"onImu can complete pair");
        check(d.arm(1600,true,false),"explicit manual rearm available");
        check(d.onGps(1000,1600,36,5,true)==null,"pre-arm data rejected");
    }
    public static void main(String[] args) { orientation();negatives();gpsFirst();System.out.println("Brake TEST detector: "+checks+" synthetic checks passed"); }
}
