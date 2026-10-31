package com.example.motonav;

/** Pure JVM checks for the P1 BLE/GPS record-only controller. */
public final class AccidentRecordCoordinatorTest {
    private static int passed;

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static AccidentImuSampleParser.Status status(boolean selfTestOk) {
        return (AccidentImuSampleParser.Status) AccidentImuSampleParser.parse(
                "IMU,STATUS,6A,1," + (selfTestOk ? "1" : "0") + ",1,1,42,0,100", 0);
    }

    private static AccidentImuSampleParser.Health health() {
        return (AccidentImuSampleParser.Health) AccidentImuSampleParser.parse(
                "IMU,HEALTH,1,1,0,0,1,0,1,0,0,0,0,1,970,0", 10);
    }

    private static AccidentImuSampleParser.Sample imu(long at) {
        return (AccidentImuSampleParser.Sample) AccidentImuSampleParser.parse(
                "IMU," + at + ",0.1,0.2,9.7,1.0,2.0,3.0,30.0", at);
    }

    public static void main(String[] args) {
        test("explicit begin waits for GPS and records only after a valid fix", () -> {
            AccidentRecordCoordinator c = new AccidentRecordCoordinator();
            require(c.begin(0, status(true), true, 1, 0, true, null) ==
                            AccidentRecordSession.State.SETUP,
                    "session armed without GPS");
            require(!c.canNotify(), "P1 exposed notification side effect");
            c.onImu(imu(100));
            require(c.snapshotSummary().frameCount == 1 &&
                            c.state() == AccidentRecordSession.State.SETUP,
                    "sample was not retained while GPS was unknown");
            c.onGps(AccidentRecordSession.GpsFix.valid(200, 31.2, 121.5, 24, 8));
            require(c.state() == AccidentRecordSession.State.ARMED,
                    "valid GPS did not arm session");
            c.onImu(imu(300));
            AccidentRecordCoordinator.Snapshot summary = c.snapshotSummary();
            require(c.state() == AccidentRecordSession.State.RECORDING &&
                            summary.frameCount == 2 && summary.gpsKnown,
                    "joined IMU/GPS frame was not recorded");
        });

        test("status and health summary are forwarded into the recorder gate", () -> {
            AccidentRecordCoordinator c = new AccidentRecordCoordinator();
            c.begin(0, status(true), true, 1, 0, true, null);
            c.onHealth(health(), 10);
            require(c.snapshotSummary().imuHealthKnown,
                    "health summary was not exposed");
            c.onGps(AccidentRecordSession.GpsFix.valid(20, 31.2, 121.5, 20, 8));
            c.onImu(imu(30));
            require(c.snapshot().get(0).health.imuSummary != null,
                    "health summary was lost from joined frame");
        });

        test("health failure is latched until a new explicit session", () -> {
            AccidentRecordCoordinator c = new AccidentRecordCoordinator();
            c.begin(0, status(true), true, 1, 0, true, null);
            c.onGps(AccidentRecordSession.GpsFix.valid(1, 31.2, 121.5, 20, 8));
            c.onStatus(status(false), true, 2, 0, true, 2);
            require(c.state() == AccidentRecordSession.State.UNAVAILABLE,
                    "failed self-test did not disable recording");
            c.onStatus(status(true), true, 3, 0, true, 3);
            require(c.state() == AccidentRecordSession.State.UNAVAILABLE,
                    "status update silently rearmed failed session");
            c.begin(4, status(true), true, 4, 0, true,
                    AccidentRecordSession.GpsFix.valid(4, 31.2, 121.5, 20, 8));
            require(c.state() == AccidentRecordSession.State.ARMED,
                    "new explicit session did not recover");
        });

        test("stop and disable provide separate retention semantics", () -> {
            AccidentRecordCoordinator c = new AccidentRecordCoordinator();
            c.begin(0, status(true), true, 1, 0, true, null);
            c.onGps(AccidentRecordSession.GpsFix.valid(1, 31.2, 121.5, 20, 8));
            c.onImu(imu(2));
            c.stop();
            require(c.state() == AccidentRecordSession.State.STOPPED &&
                            c.snapshotSummary().frameCount == 1,
                    "stop did not retain the bounded snapshot");
            c.disable();
            require(c.state() == AccidentRecordSession.State.DISABLED &&
                            c.snapshotSummary().frameCount == 0 && !c.snapshotSummary().gpsKnown,
                    "disable did not clear the session");
        });

        System.out.println("PASS: " + passed + " accident record coordinator scenarios");
    }
}
