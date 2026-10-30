package com.example.motonav;

import java.util.List;

/** Pure JVM checks for the P1 IMU/GPS record-only session boundary. */
public final class AccidentRecordSessionTest {
    private static int passed;

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static AccidentImuSampleParser.Status status() {
        return (AccidentImuSampleParser.Status) AccidentImuSampleParser.parse(
                "IMU,STATUS,6A,1,1,1,1,42,0,100", 0);
    }

    private static AccidentRecordSession.Health health(boolean gps, long at) {
        return AccidentRecordSession.Health.fromImuStatus(
                status(), true, 42, 0, true, gps, 8.0, at);
    }

    private static AccidentImuSampleParser.Sample imu(long at) {
        return (AccidentImuSampleParser.Sample) AccidentImuSampleParser.parse(
                "IMU," + at + ",0.1,0.2,9.7,1.0,2.0,3.0,30.0", at);
    }

    public static void main(String[] args) {
        test("firmware health summary is carried with every joined frame", () -> {
            AccidentRecordSession s = new AccidentRecordSession();
            AccidentRecordSession.Health base = health(true, 0);
            AccidentImuSampleParser.Health summary = (AccidentImuSampleParser.Health)
                    AccidentImuSampleParser.parse(
                            "IMU,HEALTH,1,1,0,0,1,0,1,0,0,0,0,1,970,0", 10);
            AccidentRecordSession.Health enriched = base.withImuSummary(summary, 10);
            require(enriched.imuSummary == summary && enriched.imuReadErrors == 0,
                    "health summary was not retained");
            require(s.begin(10, enriched) == AccidentRecordSession.State.ARMED,
                    "summary changed a healthy gate");
            s.onGps(AccidentRecordSession.GpsFix.valid(20, 31.2, 121.5, 20, 8));
            s.onImu(imu(30));
            require(s.snapshot().get(0).health.imuSummary == summary,
                    "frame lost firmware health summary");
        });

        test("health gate waits for GPS and records a joined frame", () -> {
            AccidentRecordSession s = new AccidentRecordSession();
            require(s.begin(0, health(false, 0)) == AccidentRecordSession.State.SETUP,
                    "session armed without a GPS fix");
            require(s.onImu(imu(100)) == AccidentRecordSession.State.SETUP,
                    "missing GPS changed state");
            require(s.snapshot().size() == 1 && !s.snapshot().get(0).hasGps(),
                    "missing GPS was not recorded as unknown");
            require(s.onGps(AccidentRecordSession.GpsFix.valid(200, 31.2, 121.5, 28, 8)) ==
                            AccidentRecordSession.State.ARMED,
                    "trusted GPS did not arm the session");
            require(s.onImu(imu(300)) == AccidentRecordSession.State.RECORDING,
                    "first joined sample did not enter recording state");
            AccidentRecordSession.Frame frame = s.snapshot().get(1);
            require(frame.hasGps() && frame.gps.speedKmh == 28 && frame.gpsAgeMs == 100,
                    "IMU/GPS merge changed the position or age");
            require(!s.canNotify(), "P1 record mode exposed a notification path");
        });

        test("IMU health failure is unavailable and never silently becomes armed", () -> {
            AccidentRecordSession s = new AccidentRecordSession();
            AccidentRecordSession.Health bad = AccidentRecordSession.Health.fromImuStatus(
                    (AccidentImuSampleParser.Status) AccidentImuSampleParser.parse(
                            "IMU,STATUS,6A,1,0,1,1,42,0,100", 0),
                    true, 42, 0, true, true, 8, 0);
            require(s.begin(0, bad) == AccidentRecordSession.State.UNAVAILABLE,
                    "failed IMU self-test was accepted");
            s.onGps(AccidentRecordSession.GpsFix.valid(100, 31.2, 121.5, 0, 8));
            require(s.state() == AccidentRecordSession.State.UNAVAILABLE,
                    "bad self-test recovered without a new health status");
            s.onImu(imu(200));
            require(s.state() == AccidentRecordSession.State.UNAVAILABLE,
                    "unavailable recorder rearmed itself");
        });

        test("duplicate, backward, and large-gap IMU samples stop the session", () -> {
            AccidentRecordSession s = new AccidentRecordSession();
            s.begin(0, health(true, 0));
            s.onGps(AccidentRecordSession.GpsFix.valid(10, 31.2, 121.5, 20, 8));
            s.onImu(imu(100));
            s.onImu(imu(100));
            require(s.state() == AccidentRecordSession.State.UNAVAILABLE && s.invalidSamples() == 1,
                    "duplicate sample did not invalidate the stream");
            s.begin(200, health(true, 200));
            s.onGps(AccidentRecordSession.GpsFix.valid(210, 31.2, 121.5, 20, 8));
            s.onImu(imu(300));
            s.onImu(imu(2_500));
            require(s.state() == AccidentRecordSession.State.UNAVAILABLE && s.invalidSamples() == 1,
                    "large gap did not invalidate the stream");
        });

        test("ring buffer drops oldest frames and preserves newest evidence", () -> {
            AccidentRecordSession.Config c = new AccidentRecordSession.Config(750, 2_000, 5_000, 100, 2);
            AccidentRecordSession s = new AccidentRecordSession(c);
            s.begin(0, health(true, 0));
            s.onGps(AccidentRecordSession.GpsFix.valid(0, 31.2, 121.5, 0, 8));
            s.onImu(imu(100));
            s.onImu(imu(200));
            s.onImu(imu(300));
            List<AccidentRecordSession.Frame> frames = s.snapshot();
            require(frames.size() == 2 && s.droppedFrames() == 1,
                    "bounded buffer did not drop exactly one frame");
            require(frames.get(0).sequence == 2 && frames.get(1).sequence == 3,
                    "newest frames were not retained in order");
        });

        test("stale or invalid GPS is explicit and cannot be used for a frame", () -> {
            AccidentRecordSession s = new AccidentRecordSession();
            s.begin(0, health(false, 0));
            s.onGps(AccidentRecordSession.GpsFix.invalid(100, 31.2, 121.5, 12, 300));
            require(s.state() == AccidentRecordSession.State.SETUP,
                    "invalid GPS fix armed the session");
            s.onImu(imu(1_000));
            require(!s.snapshot().get(0).hasGps() && s.snapshot().get(0).gpsAgeMs == -1,
                    "invalid/stale GPS was attached as trusted evidence");
        });

        System.out.println("PASS: " + passed + " accident record session scenarios");
    }
}
