package com.example.motonav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure JVM recorder for the P1 motorcycle incident work.
 *
 * <p>The recorder joins the diagnostic IMU stream with the phone's GPS fixes,
 * keeps a bounded in-memory ring, and exposes the health gates that a later
 * safety state machine will need. It deliberately has no Android, contacts,
 * SMS, dialer, notification, or network dependency. {@link #canNotify()} is
 * always false by design: P1 only collects evidence and never contacts anyone.</p>
 *
 * <p>This is a data and lifecycle boundary, not a calibrated accident
 * detector. A frame being recorded does not mean that an accident occurred,
 * and a missing GPS match is recorded as an unknown instead of being guessed.</p>
 */
public final class AccidentRecordSession {
    public enum State {
        DISABLED,
        SETUP,
        ARMED,
        RECORDING,
        UNAVAILABLE,
        STOPPED
    }

    /** Bounds that prevent an accidental high-rate stream from growing forever. */
    public static final class Config {
        public final long gpsMergeWindowMs;
        public final long maximumImuGapMs;
        public final long maximumGpsAgeMs;
        public final double maximumGpsAccuracyM;
        public final int maximumFrames;

        public Config(
                long gpsMergeWindowMs,
                long maximumImuGapMs,
                long maximumGpsAgeMs,
                double maximumGpsAccuracyM,
                int maximumFrames) {
            if (gpsMergeWindowMs <= 0 || maximumImuGapMs <= 0 || maximumGpsAgeMs <= 0 ||
                    !Double.isFinite(maximumGpsAccuracyM) || maximumGpsAccuracyM <= 0 ||
                    maximumFrames <= 0) {
                throw new IllegalArgumentException("invalid accident recorder configuration");
            }
            this.gpsMergeWindowMs = gpsMergeWindowMs;
            this.maximumImuGapMs = maximumImuGapMs;
            this.maximumGpsAgeMs = maximumGpsAgeMs;
            this.maximumGpsAccuracyM = maximumGpsAccuracyM;
            this.maximumFrames = maximumFrames;
        }

        /** Conservative replay values; they are not accident thresholds. */
        public static Config defaults() {
            return new Config(750L, 2_000L, 5_000L, 100.0, 600);
        }
    }

    /**
     * The health snapshot carried with every recorded frame. Values are kept
     * explicit so a future decision layer can refuse an unknown condition.
     */
    public static final class Health {
        public final boolean imuPresent;
        public final boolean selfTestKnown;
        public final boolean selfTestOk;
        public final boolean imuConfigured;
        public final boolean imuStreamEnabled;
        public final long imuReadErrors;
        public final boolean bleConnected;
        public final long blePacketsReceived;
        public final long blePacketsLost;
        public final boolean powerHealthy;
        public final boolean gpsValid;
        public final double gpsAccuracyM;
        public final long observedAtMonotonicMs;
        /** Latest versioned firmware health summary, when one was received. */
        public final AccidentImuSampleParser.Health imuSummary;

        public Health(
                boolean imuPresent,
                boolean selfTestKnown,
                boolean selfTestOk,
                boolean imuConfigured,
                boolean imuStreamEnabled,
                long imuReadErrors,
                boolean bleConnected,
                long blePacketsReceived,
                long blePacketsLost,
                boolean powerHealthy,
                boolean gpsValid,
                double gpsAccuracyM,
                long observedAtMonotonicMs) {
            this(imuPresent, selfTestKnown, selfTestOk, imuConfigured, imuStreamEnabled,
                    imuReadErrors, bleConnected, blePacketsReceived, blePacketsLost,
                    powerHealthy, gpsValid, gpsAccuracyM, observedAtMonotonicMs, null);
        }

        private Health(
                boolean imuPresent,
                boolean selfTestKnown,
                boolean selfTestOk,
                boolean imuConfigured,
                boolean imuStreamEnabled,
                long imuReadErrors,
                boolean bleConnected,
                long blePacketsReceived,
                long blePacketsLost,
                boolean powerHealthy,
                boolean gpsValid,
                double gpsAccuracyM,
                long observedAtMonotonicMs,
                AccidentImuSampleParser.Health imuSummary) {
            if (imuReadErrors < 0 || blePacketsReceived < 0 || blePacketsLost < 0 ||
                    observedAtMonotonicMs < 0) {
                throw new IllegalArgumentException("health counters and time must be non-negative");
            }
            if (!Double.isFinite(gpsAccuracyM) || gpsAccuracyM < 0) {
                throw new IllegalArgumentException("invalid GPS accuracy");
            }
            this.imuPresent = imuPresent;
            this.selfTestKnown = selfTestKnown;
            this.selfTestOk = selfTestOk;
            this.imuConfigured = imuConfigured;
            this.imuStreamEnabled = imuStreamEnabled;
            this.imuReadErrors = imuReadErrors;
            this.bleConnected = bleConnected;
            this.blePacketsReceived = blePacketsReceived;
            this.blePacketsLost = blePacketsLost;
            this.powerHealthy = powerHealthy;
            this.gpsValid = gpsValid;
            this.gpsAccuracyM = gpsAccuracyM;
            this.observedAtMonotonicMs = observedAtMonotonicMs;
            this.imuSummary = imuSummary;
        }

        public static Health unavailable(long at) {
            return new Health(false, false, false, false, false, 0,
                    false, 0, 0, false, false, 0.0, at);
        }

        /** Build a health snapshot from the firmware status and BLE counters. */
        public static Health fromImuStatus(
                AccidentImuSampleParser.Status status,
                boolean bleConnected,
                long blePacketsReceived,
                long blePacketsLost,
                boolean powerHealthy,
                boolean gpsValid,
                double gpsAccuracyM,
                long observedAtMonotonicMs) {
            if (status == null) throw new IllegalArgumentException("IMU status is required");
            return new Health(status.present, status.selfTestKnown, status.selfTestOk,
                    status.configured, status.streamEnabled, status.readErrors,
                    bleConnected, blePacketsReceived, blePacketsLost, powerHealthy,
                    gpsValid, gpsAccuracyM, observedAtMonotonicMs);
        }

        /** Combine the legacy STATUS flags with the optional versioned summary. */
        public static Health fromImuStatusAndSummary(
                AccidentImuSampleParser.Status status,
                AccidentImuSampleParser.Health summary,
                boolean bleConnected,
                long blePacketsReceived,
                long blePacketsLost,
                boolean powerHealthy,
                boolean gpsValid,
                double gpsAccuracyM,
                long observedAtMonotonicMs) {
            if (summary == null) throw new IllegalArgumentException("IMU summary is required");
            return fromImuStatus(status, bleConnected, blePacketsReceived, blePacketsLost,
                    powerHealthy, gpsValid, gpsAccuracyM, observedAtMonotonicMs)
                    .withImuSummary(summary, observedAtMonotonicMs);
        }

        public Health withGps(GpsFix fix, long observedAt) {
            if (fix == null) throw new IllegalArgumentException("GPS fix is required");
            return new Health(imuPresent, selfTestKnown, selfTestOk, imuConfigured,
                    imuStreamEnabled, imuReadErrors, bleConnected, blePacketsReceived,
                    blePacketsLost, powerHealthy, fix.valid, fix.accuracyM, observedAt, imuSummary);
        }

        /** Attach the latest firmware summary while preserving link/GPS gates. */
        public Health withImuSummary(AccidentImuSampleParser.Health summary, long observedAt) {
            if (summary == null) throw new IllegalArgumentException("IMU summary is required");
            return new Health(imuPresent, selfTestKnown, selfTestOk, imuConfigured,
                    imuStreamEnabled, Math.max(imuReadErrors, summary.readErrors),
                    bleConnected, blePacketsReceived, blePacketsLost, powerHealthy,
                    gpsValid, gpsAccuracyM, observedAt, summary);
        }

        /** Sensor/link conditions needed before the future detector may arm. */
        public boolean coreReady() {
            return imuPresent && selfTestKnown && selfTestOk && imuConfigured &&
                    imuStreamEnabled && imuReadErrors == 0 && bleConnected &&
                    powerHealthy && blePacketsLost == 0;
        }

        public boolean gpsReady(Config config) {
            return gpsValid && gpsAccuracyM <= config.maximumGpsAccuracyM;
        }

        public boolean readyForDecision(Config config) {
            return coreReady() && gpsReady(config);
        }

        public boolean finite() {
            return Double.isFinite(gpsAccuracyM) && gpsAccuracyM >= 0;
        }
    }

    /** Phone location/velocity sample. Coordinates are expected in GCJ-02. */
    public static final class GpsFix {
        public final long monotonicMs;
        public final double latitude;
        public final double longitude;
        public final double speedKmh;
        public final double accuracyM;
        public final boolean valid;

        public GpsFix(long monotonicMs, double latitude, double longitude,
                      double speedKmh, double accuracyM, boolean valid) {
            if (monotonicMs < 0 || !Double.isFinite(latitude) || !Double.isFinite(longitude) ||
                    !Double.isFinite(speedKmh) || speedKmh < 0 || !Double.isFinite(accuracyM) ||
                    accuracyM < 0 || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("invalid GPS fix");
            }
            this.monotonicMs = monotonicMs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedKmh = speedKmh;
            this.accuracyM = accuracyM;
            this.valid = valid;
        }

        public static GpsFix valid(long at, double lat, double lng, double speedKmh, double accuracyM) {
            return new GpsFix(at, lat, lng, speedKmh, accuracyM, true);
        }

        public static GpsFix invalid(long at, double lat, double lng, double speedKmh, double accuracyM) {
            return new GpsFix(at, lat, lng, speedKmh, accuracyM, false);
        }

        public boolean usable(Config config) {
            return valid && accuracyM <= config.maximumGpsAccuracyM;
        }
    }

    /** One IMU sample and the closest trusted GPS fix known at that time. */
    public static final class Frame {
        public final long sequence;
        public final long receivedAtMonotonicMs;
        public final AccidentImuSampleParser.Sample imu;
        public final GpsFix gps;
        public final long gpsAgeMs;
        public final Health health;

        private Frame(long sequence, long receivedAtMonotonicMs,
                      AccidentImuSampleParser.Sample imu, GpsFix gps,
                      long gpsAgeMs, Health health) {
            this.sequence = sequence;
            this.receivedAtMonotonicMs = receivedAtMonotonicMs;
            this.imu = imu;
            this.gps = gps;
            this.gpsAgeMs = gpsAgeMs;
            this.health = health;
        }

        public boolean hasGps() {
            return gps != null && gps.valid;
        }
    }

    private final Config config;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private State state = State.DISABLED;
    private Health health;
    private GpsFix latestGps;
    private long lastImuAt = -1;
    private long lastGpsAt = -1;
    private long sequence;
    private long droppedFrames;
    private long invalidSamples;

    public AccidentRecordSession() {
        this(Config.defaults());
    }

    public AccidentRecordSession(Config config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        this.config = config;
        this.health = Health.unavailable(0);
    }

    public State state() {
        return state;
    }

    public Health health() {
        return health;
    }

    public long droppedFrames() {
        return droppedFrames;
    }

    public long invalidSamples() {
        return invalidSamples;
    }

    /** P1 has no notification side effect, even after a long recording. */
    public boolean canNotify() {
        return false;
    }

    /** Start an explicitly user initiated record session. */
    public State begin(long atMonotonicMs, Health initialHealth) {
        if (atMonotonicMs < 0 || initialHealth == null) {
            throw new IllegalArgumentException("session start requires time and health");
        }
        frames.clear();
        latestGps = null;
        lastImuAt = -1;
        lastGpsAt = -1;
        sequence = 0;
        droppedFrames = 0;
        invalidSamples = 0;
        health = initialHealth;
        state = State.SETUP;
        evaluateState(atMonotonicMs);
        return state;
    }

    public void stop() {
        if (state != State.DISABLED) state = State.STOPPED;
    }

    public void disable() {
        state = State.DISABLED;
        frames.clear();
        latestGps = null;
        lastImuAt = -1;
        lastGpsAt = -1;
    }

    /** Update IMU/link/power status without making a safety decision. */
    public State updateHealth(Health next) {
        if (next == null) throw new IllegalArgumentException("health is required");
        health = next;
        if (state == State.DISABLED || state == State.STOPPED) return state;
        evaluateState(next.observedAtMonotonicMs);
        return state;
    }

    /**
     * Feed a GPS fix. Invalid fixes are retained only as health evidence and
     * never become a location attached to an IMU frame.
     */
    public State onGps(GpsFix fix) {
        if (fix == null || state == State.DISABLED || state == State.STOPPED) return state;
        if (lastGpsAt >= 0 && fix.monotonicMs <= lastGpsAt) {
            invalidSamples++;
            state = State.UNAVAILABLE;
            return state;
        }
        lastGpsAt = fix.monotonicMs;
        latestGps = fix;
        health = health.withGps(fix, fix.monotonicMs);
        evaluateState(fix.monotonicMs);
        return state;
    }

    /** Feed one parsed diagnostic IMU sample and append one bounded frame. */
    public State onImu(AccidentImuSampleParser.Sample sample) {
        if (sample == null || state == State.DISABLED || state == State.STOPPED) return state;
        if (!sample.finite() || lastImuAt >= 0 && sample.receivedAtMonotonicMs <= lastImuAt ||
                lastImuAt >= 0 && sample.receivedAtMonotonicMs - lastImuAt > config.maximumImuGapMs) {
            invalidSamples++;
            state = State.UNAVAILABLE;
            return state;
        }
        lastImuAt = sample.receivedAtMonotonicMs;
        evaluateState(sample.receivedAtMonotonicMs);
        GpsFix gps = latestGps;
        long age = Long.MAX_VALUE;
        if (gps != null) {
            long delta = sample.receivedAtMonotonicMs - gps.monotonicMs;
            age = Math.abs(delta);
            if (!gps.valid || age > config.gpsMergeWindowMs ||
                    sample.receivedAtMonotonicMs - gps.monotonicMs > config.maximumGpsAgeMs) gps = null;
        }
        append(new Frame(++sequence, sample.receivedAtMonotonicMs, sample, gps,
                gps == null ? -1 : age, health));
        if (state == State.ARMED) state = State.RECORDING;
        return state;
    }

    /** Mark stale input without inventing a location or an accident. */
    public State tick(long nowMonotonicMs) {
        if (nowMonotonicMs < 0 || state == State.DISABLED || state == State.STOPPED) return state;
        if (lastImuAt >= 0 && nowMonotonicMs - lastImuAt > config.maximumImuGapMs) {
            state = State.UNAVAILABLE;
        } else {
            evaluateState(nowMonotonicMs);
        }
        return state;
    }

    public List<Frame> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(frames));
    }

    private void append(Frame frame) {
        if (frames.size() >= config.maximumFrames) {
            frames.removeFirst();
            droppedFrames++;
        }
        frames.addLast(frame);
    }

    private void evaluateState(long at) {
        if (state == State.DISABLED || state == State.STOPPED) return;
        if (!health.coreReady()) {
            state = State.UNAVAILABLE;
            return;
        }
        if (!health.gpsReady(config)) {
            state = State.SETUP;
            return;
        }
        if (state == State.SETUP || state == State.UNAVAILABLE) state = State.ARMED;
    }
}
