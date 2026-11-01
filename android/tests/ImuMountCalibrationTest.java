package com.example.motonav;

import java.util.Locale;

/** Geometry/protocol checks; they do not certify an on-road lean or accident detector. */
public final class ImuMountCalibrationTest {
    private static int passed;

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double tolerance) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                "expected " + expected + ", got " + actual);
    }

    private static ImuMountCalibration.Frame reference(double[] v) {
        ImuMountCalibration.Frame frame = ImuMountCalibration.parse(String.format(Locale.ROOT,
                "IMUCAL,1,3,17,%.12f,%.12f,%.12f,320,0", v[0], v[1], v[2]));
        require(frame != null && frame.hasReference(), "valid gravity reference rejected");
        return frame;
    }

    private static double tilt(ImuMountCalibration.Frame frame, double[] sample) {
        return ImuMountCalibration.relativeTiltDeg(frame, sample[0], sample[1], sample[2]);
    }

    private static double[] rotate(double[] v, double[] axis, double degrees) {
        double length = Math.hypot(Math.hypot(axis[0], axis[1]), axis[2]);
        double x = axis[0] / length, y = axis[1] / length, z = axis[2] / length;
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        double dot = x * v[0] + y * v[1] + z * v[2];
        return new double[] {
                v[0] * c + (y * v[2] - z * v[1]) * s + x * dot * (1 - c),
                v[1] * c + (z * v[0] - x * v[2]) * s + y * dot * (1 - c),
                v[2] * c + (x * v[1] - y * v[0]) * s + z * dot * (1 - c)
        };
    }

    public static void main(String[] args) {
        test("nine-field versioned frame retains raw reference and unsigned counters", () -> {
            ImuMountCalibration.Frame f = ImuMountCalibration.parse(
                    "IMUCAL,1,3,4294967295,-0.461,-0.597,9.037,4294967295,0");
            require(f != null && f.state == ImuMountCalibration.READY && f.hasReference(), "frame rejected");
            require(f.revision == 4294967295L && f.sampleCount == 4294967295L && f.reason == 0,
                    "unsigned counters changed");
            near(f.referenceX, -0.461, 0);
            near(f.referenceY, -0.597, 0);
            near(f.referenceZ, 9.037, 0);
        });

        test("upright reference can be any mounting axis, including vertical screen", () -> {
            double[][] mounts = {{0, 9.06, 0}, {0, 0, -10.57}, {9.3, 0, 0}, {3, -4, 8}};
            for (double[] mount : mounts) near(tilt(reference(mount), mount), 0, 0.00001);
            ImuMountCalibration.Frame f = reference(new double[] {0, 9.06, 0});
            for (double angle : new double[] {30, 90, 180}) {
                double radians = Math.toRadians(angle);
                near(ImuMountCalibration.relativeTiltDeg(f, 0, 9.06 * Math.cos(radians),
                        9.06 * Math.sin(radians)), angle, 0.00001);
            }
        });

        test("arbitrary common installation rotations preserve the relative angle", () -> {
            double[] baseline = {0, 9.81, 0};
            double[] moved = rotate(baseline, new double[] {1, 0, 0}, 37);
            for (double degrees : new double[] {0, 7, 45, 89, 137, 180, 271, 359}) {
                double[] ref = rotate(baseline, new double[] {2, -3, 5}, degrees);
                double[] now = rotate(moved, new double[] {2, -3, 5}, degrees);
                near(tilt(reference(ref), now), 37, 0.00001);
            }
        });

        test("opposite directions have the same total angle but inversion remains 180", () -> {
            ImuMountCalibration.Frame f = reference(new double[] {0, 0, 9.81});
            near(ImuMountCalibration.relativeTiltDeg(f, 4.905, 0, 8.495709211), 30, 0.00001);
            near(ImuMountCalibration.relativeTiltDeg(f, -4.905, 0, 8.495709211), 30, 0.00001);
            near(ImuMountCalibration.relativeTiltDeg(f, 0, 0, -9.81), 180, 0);
            near(ImuMountCalibration.relativeTiltDeg(f, 0, 9.81, 0), 90, 0);
        });

        test("zero reference states are readable but never calculate an angle", () -> {
            for (int state : new int[] {0, 1, 2, 4}) {
                ImuMountCalibration.Frame f = ImuMountCalibration.parse("IMUCAL,1," + state + ",0,0,0,0,0,0");
                require(f != null && !f.hasReference(), "no-reference state rejected");
                require(Double.isNaN(ImuMountCalibration.relativeTiltDeg(f, 0, 0, 9.81)),
                        "invented angle without reference");
            }
        });

        test("collecting and failed operations preserve an existing usable reference", () -> {
            for (int state : new int[] {2, 4}) {
                ImuMountCalibration.Frame f = ImuMountCalibration.parse(
                        "IMUCAL,1," + state + ",7,0,9.06,0,81,5");
                require(f != null && f.hasReference() && f.revision == 7 && f.reason == 5,
                        "old reference or failure reason lost");
                near(ImuMountCalibration.relativeTiltDeg(f, 9.06, 0, 0), 90, 0);
            }
        });

        test("malformed incompatible and inconsistent frames are rejected", () -> {
            String[] bad = {
                    null, "", "IMU,1,3,1,0,0,9.81,300,0", "IMUCAL,2,3,1,0,0,9.81,300,0",
                    "IMUCAL,1,3,1,0,0,9.81,300", "IMUCAL,1,3,1,0,0,9.81,300,0,extra",
                    "IMUCAL,1,3,1,0,0,9.81,300,", "IMUCAL,1,5,1,0,0,9.81,300,0",
                    "IMUCAL,1,-1,1,0,0,9.81,300,0", "IMUCAL,1,3,-1,0,0,9.81,300,0",
                    "IMUCAL,1,3,4294967296,0,0,9.81,300,0",
                    "IMUCAL,1,3,99999999999999999999,0,0,9.81,300,0",
                    "IMUCAL,1,3,1,0,0,9.81,4294967296,0", "IMUCAL,1,3,1,0,0,9.81,-1,0",
                    "IMUCAL,1,3,1,0,0,9.81,300,256", "IMUCAL,1,3,1,0,0,9.81,300,-1",
                    "IMUCAL,1,3,1,NaN,0,9.81,300,0", "IMUCAL,1,3,1,0,0,Infinity,300,0",
                    "IMUCAL,1,3,1,0,0,1e309,300,0", "IMUCAL,1,3,1,0,0,0,300,0",
                    "IMUCAL,1,3,1,0,0,7.49,300,0", "IMUCAL,1,3,1,0,0,12.51,300,0",
                    "IMUCAL,1,3,1,9,9,0,300,0", "IMUCAL,1,3,0,0,0,9.81,300,0",
                    "IMUCAL,1,3,0,0,0,0,300,0", "IMUCAL,1,1,1,0,0,9.81,300,0",
                    "IMUCAL,1,0,1,0,0,9.81,300,0", "IMUCAL,1,4,0,0,0,0.1,0,1",
                    "IMUCAL,1,3,1,0,0,0x1.39p3,300,0", "IMUCAL,1,3,1,0,0,9.81f,300,0",
                    "IMUCAL,1,3,1,0, 0,9.81,300,0"
            };
            for (String value : bad) require(ImuMountCalibration.parse(value) == null,
                    "accepted invalid frame: " + value);
        });

        test("finite physical boundaries and near-zero samples fail closed", () -> {
            ImuMountCalibration.Frame f = reference(new double[] {0, 0, 9.81});
            double[][] bad = {{0, 0, 0}, {0, 0, 1e-8}, {Double.NaN, 0, 9.81},
                    {0, Double.POSITIVE_INFINITY, 9.81}, {0, 0, 201}, {Double.MAX_VALUE, Double.MAX_VALUE, 0}};
            for (double[] value : bad) require(Double.isNaN(tilt(f, value)), "invalid sample accepted");
            require(Double.isNaN(tilt(null, new double[] {0, 0, 9.81})), "null frame accepted");
            near(ImuMountCalibration.relativeTiltDeg(f, 0, 0, 7.5), 0, 0);
            near(tilt(reference(new double[] {0, 7.5, 0}), new double[] {0, 7.5, 0}), 0, 0);
            near(tilt(reference(new double[] {0, 12.5, 0}), new double[] {0, 12.5, 0}), 0, 0);
        });

        test("user four-pose means remain raw rather than becoming a sensor bias calibration", () -> {
            double[][] poses = {{-0.461, -0.597, 9.037}, {-0.481, -0.541, -10.573},
                    {0.658, -10.390, -1.389}, {-0.744, 9.171, -1.346}};
            for (double[] pose : poses) {
                ImuMountCalibration.Frame f = reference(pose);
                near(f.referenceX, pose[0], 0.00000000001);
                near(f.referenceY, pose[1], 0.00000000001);
                near(f.referenceZ, pose[2], 0.00000000001);
                near(tilt(f, pose), 0, 0.00001);
                require(Math.abs(Math.hypot(Math.hypot(f.referenceX, f.referenceY), f.referenceZ) - 9.80665) > 0.1,
                        "raw magnitude silently forced to standard gravity");
            }
            // Measured offsets make the real opposite poses imperfect; preserve that evidence.
            double opposite = tilt(reference(poses[0]), poses[1]);
            require(opposite > 170 && opposite < 180, "raw offset evidence was lost");
        });

        System.out.println("PASS: " + passed + " installation-reference scenarios");
    }
}
