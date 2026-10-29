package com.example.motonav;

import java.util.Locale;

/**
 * Parses the opt-in diagnostic IMU frames emitted by the 1.75C firmware.
 *
 * <p>The parser has no Android or BLE dependency so replay tests can verify
 * the wire format before a safety decision is connected to it. Firmware
 * timestamps are kept as boardMillis; callers must use receivedAtMonotonicMs
 * when joining the sample with the phone's GPS clock.</p>
 */
public final class AccidentImuSampleParser {
    private AccidentImuSampleParser() {}

    public abstract static class Frame {
        private Frame() {}
    }

    public static final class Status extends Frame {
        /** I2C address reported by firmware, or -1 for the legacy frame. */
        public final int address;
        public final boolean addressKnown;
        public final boolean selfTestOk;
        public final boolean selfTestKnown;
        public final boolean present;
        public final boolean configured;
        public final boolean streamEnabled;
        public final long samples;
        public final long readErrors;
        public final long lastBoardMillis;

        private Status(
                int address,
                Boolean selfTestOk,
                boolean present,
                boolean configured,
                boolean streamEnabled,
                long samples,
                long readErrors,
                long lastBoardMillis) {
            this.address = address;
            this.addressKnown = address >= 0;
            this.selfTestKnown = selfTestOk != null;
            this.selfTestOk = selfTestOk == null || selfTestOk;
            this.present = present;
            this.configured = configured;
            this.streamEnabled = streamEnabled;
            this.samples = samples;
            this.readErrors = readErrors;
            this.lastBoardMillis = lastBoardMillis;
        }

        public boolean usable() {
            return present && selfTestKnown && selfTestOk && configured && readErrors == 0;
        }
    }

    public static final class Sample extends Frame {
        public final long boardMillis;
        public final long receivedAtMonotonicMs;
        public final double accelerationXMps2;
        public final double accelerationYMps2;
        public final double accelerationZMps2;
        public final double gyroXDegPerSec;
        public final double gyroYDegPerSec;
        public final double gyroZDegPerSec;
        public final double temperatureC;

        private Sample(
                long boardMillis,
                long receivedAtMonotonicMs,
                double accelerationXMps2,
                double accelerationYMps2,
                double accelerationZMps2,
                double gyroXDegPerSec,
                double gyroYDegPerSec,
                double gyroZDegPerSec,
                double temperatureC) {
            this.boardMillis = boardMillis;
            this.receivedAtMonotonicMs = receivedAtMonotonicMs;
            this.accelerationXMps2 = accelerationXMps2;
            this.accelerationYMps2 = accelerationYMps2;
            this.accelerationZMps2 = accelerationZMps2;
            this.gyroXDegPerSec = gyroXDegPerSec;
            this.gyroYDegPerSec = gyroYDegPerSec;
            this.gyroZDegPerSec = gyroZDegPerSec;
            this.temperatureC = temperatureC;
        }

        public double accelerationMagnitudeMps2() {
            return magnitude(accelerationXMps2, accelerationYMps2, accelerationZMps2);
        }

        public double gyroMagnitudeDegPerSec() {
            return magnitude(gyroXDegPerSec, gyroYDegPerSec, gyroZDegPerSec);
        }

        public boolean finite() {
            return boardMillis >= 0 && receivedAtMonotonicMs >= 0 &&
                    isFiniteNumber(accelerationXMps2) && isFiniteNumber(accelerationYMps2) &&
                    isFiniteNumber(accelerationZMps2) && isFiniteNumber(gyroXDegPerSec) &&
                    isFiniteNumber(gyroYDegPerSec) && isFiniteNumber(gyroZDegPerSec) &&
                    isFiniteNumber(temperatureC);
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "IMU,%d,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f",
                    boardMillis, accelerationXMps2, accelerationYMps2,
                    accelerationZMps2, gyroXDegPerSec, gyroYDegPerSec,
                    gyroZDegPerSec, temperatureC);
        }
    }

    /** Parse a BLE notification; malformed or unrelated messages return null. */
    public static Frame parse(String message, long receivedAtMonotonicMs) {
        if (message == null || receivedAtMonotonicMs < 0) return null;
        String[] p = message.trim().split(",", -1);
        if (p.length < 2 || !"IMU".equals(p[0])) return null;
        if ("STATUS".equals(p[1])) {
            // Current firmware includes the detected I2C address. Accept the
            // older address-less frame while diagnostic images are upgraded.
            if (p.length != 8 && p.length != 9 && p.length != 10) return null;
            int address = p.length >= 9 ? hexAddress(p[2]) : -1;
            if (p.length >= 9 && address < 0) return null;
            Boolean selfTest = p.length == 10 ? bool(p[4]) : null;
            int presentIndex = p.length >= 9 ? 3 : 2;
            int configuredIndex = p.length == 10 ? 5 : presentIndex + 1;
            Boolean present = bool(p[presentIndex]);
            Boolean configured = bool(p[configuredIndex]);
            Boolean stream = bool(p[configuredIndex + 1]);
            Long samples = nonNegativeLong(p[configuredIndex + 2]);
            Long errors = nonNegativeLong(p[configuredIndex + 3]);
            Long last = nonNegativeLong(p[configuredIndex + 4]);
            if (present == null || configured == null || stream == null ||
                    samples == null || errors == null || last == null ||
                    (p.length == 10 && selfTest == null)) return null;
            return new Status(address, selfTest, present, configured, stream, samples, errors, last);
        }
        // BLE sample format: IMU,boardMillis,ax,ay,az,gx,gy,gz,temp
        if (p.length != 9) return null;
        Long boardMillis = nonNegativeLong(p[1]);
        Double ax = finiteDouble(p[2]);
        Double ay = finiteDouble(p[3]);
        Double az = finiteDouble(p[4]);
        Double gx = finiteDouble(p[5]);
        Double gy = finiteDouble(p[6]);
        Double gz = finiteDouble(p[7]);
        Double temp = finiteDouble(p[8]);
        if (boardMillis == null || ax == null || ay == null || az == null ||
                gx == null || gy == null || gz == null || temp == null) return null;
        Sample sample = new Sample(boardMillis, receivedAtMonotonicMs,
                ax, ay, az, gx, gy, gz, temp);
        return sample.finite() ? sample : null;
    }

    private static Boolean bool(String value) {
        if ("0".equals(value)) return Boolean.FALSE;
        if ("1".equals(value)) return Boolean.TRUE;
        return null;
    }

    private static Long nonNegativeLong(String value) {
        try {
            long n = Long.parseLong(value);
            return n >= 0 ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int hexAddress(String value) {
        if (value == null || value.length() != 2) return -1;
        try {
            int address = Integer.parseInt(value, 16);
            return address >= 0 && address <= 0x7F ? address : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Double finiteDouble(String value) {
        try {
            double n = Double.parseDouble(value);
            return Double.isFinite(n) ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isFiniteNumber(double value) {
        return Double.isFinite(value);
    }

    private static double magnitude(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
