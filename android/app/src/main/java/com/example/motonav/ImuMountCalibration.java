package com.example.motonav;

import java.util.regex.Pattern;

/**
 * Versioned installation reference for display-only relative total inclination.
 *
 * <p>A single stationary gravity vector does not identify vehicle forward or signed
 * left/right lean. Acceleration and cornering can change this estimate. This class
 * never changes raw acceleration, gyro bias, or accident-detection thresholds.</p>
 */
public final class ImuMountCalibration {
    public static final int UNSUPPORTED = 0;
    public static final int UNCALIBRATED = 1;
    public static final int COLLECTING = 2;
    public static final int READY = 3;
    public static final int FAILED = 4;
    private static final long UINT32_MAX = 0xFFFF_FFFFL;
    private static final double MIN_REFERENCE_MAGNITUDE = 7.5;
    private static final double MAX_REFERENCE_MAGNITUDE = 12.5;
    private static final double MIN_SAMPLE_MAGNITUDE = 1e-6;
    private static final double MAX_SAMPLE_MAGNITUDE = 200.0;
    private static final Pattern UNSIGNED = Pattern.compile("[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private ImuMountCalibration() {}

    public static final class Frame {
        public final int state;
        public final long revision;
        public final double referenceX;
        public final double referenceY;
        public final double referenceZ;
        public final long sampleCount;
        public final int reason;

        private Frame(int state, long revision, double referenceX, double referenceY,
                      double referenceZ, long sampleCount, int reason) {
            this.state = state;
            this.revision = revision;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
            this.referenceZ = referenceZ;
            this.sampleCount = sampleCount;
            this.reason = reason;
        }

        /** A collecting/failed operation may retain the last valid reference. */
        public boolean hasReference() { return revision > 0L; }
    }

    /** Parse exactly IMUCAL,1,state,revision,refAx,refAy,refAz,count,reason (9 fields). */
    public static Frame parse(String message) {
        if (message == null || message.length() > 256) return null;
        String[] p = message.trim().split(",", -1);
        if (p.length != 9 || !"IMUCAL".equals(p[0]) || !"1".equals(p[1])) return null;
        try {
            long state = unsigned(p[2], FAILED);
            long revision = unsigned(p[3], UINT32_MAX);
            double x = decimal(p[4]);
            double y = decimal(p[5]);
            double z = decimal(p[6]);
            long count = unsigned(p[7], UINT32_MAX);
            long reason = unsigned(p[8], 255L);
            double magnitude = magnitude(x, y, z);
            if (revision == 0L) {
                if (x != 0.0 || y != 0.0 || z != 0.0 || state == READY) return null;
            } else if (state == UNSUPPORTED || state == UNCALIBRATED ||
                    magnitude < MIN_REFERENCE_MAGNITUDE || magnitude > MAX_REFERENCE_MAGNITUDE) {
                return null;
            }
            return new Frame((int) state, revision, x, y, z, count, (int) reason);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Angle to the installation reference, 0..180 degrees, without left/right sign.
     * NaN means unavailable/invalid. In particular, inversion must remain 180, not 0.
     */
    public static double relativeTiltDeg(Frame frame, double ax, double ay, double az) {
        if (frame == null || !frame.hasReference() || !Double.isFinite(ax) ||
                !Double.isFinite(ay) || !Double.isFinite(az)) return Double.NaN;
        double sampleMagnitude = magnitude(ax, ay, az);
        if (!Double.isFinite(sampleMagnitude) || sampleMagnitude <= MIN_SAMPLE_MAGNITUDE ||
                sampleMagnitude > MAX_SAMPLE_MAGNITUDE) return Double.NaN;
        double referenceMagnitude = magnitude(frame.referenceX, frame.referenceY, frame.referenceZ);
        double dot = (frame.referenceX / referenceMagnitude) * (ax / sampleMagnitude) +
                (frame.referenceY / referenceMagnitude) * (ay / sampleMagnitude) +
                (frame.referenceZ / referenceMagnitude) * (az / sampleMagnitude);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    private static long unsigned(String value, long max) {
        if (!UNSIGNED.matcher(value).matches()) throw new IllegalArgumentException();
        long parsed = Long.parseLong(value);
        if (parsed > max) throw new IllegalArgumentException();
        return parsed;
    }

    private static double decimal(String value) {
        if (!DECIMAL.matcher(value).matches()) throw new IllegalArgumentException();
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException();
        return parsed;
    }

    private static double magnitude(double x, double y, double z) {
        return Math.hypot(Math.hypot(x, y), z);
    }
}
