package com.example.motonav;

import java.util.List;

/**
 * Android-independent controller for the P1, record-only accident work.
 *
 * <p>This class is the small integration boundary between the BLE callbacks
 * and {@link AccidentRecordSession}.  It intentionally has no Android,
 * location, contact, SMS, dialer, or notification dependency.  A caller must
 * explicitly start a session and provide the current firmware status before
 * samples are accepted.  A health failure is latched by the underlying
 * session and requires a new explicit session before recording can resume.</p>
 *
 * <p>It is important that this class does not turn an IMU frame into an
 * accident verdict.  {@link #canNotify()} is permanently false in P1.</p>
 */
public final class AccidentRecordCoordinator {
    /** Compact, read-only counters suitable for a diagnostics page. */
    public static final class Snapshot {
        public final AccidentRecordSession.State state;
        public final int frameCount;
        public final long droppedFrames;
        public final long invalidSamples;
        public final boolean gpsKnown;
        public final boolean imuHealthKnown;
        public final long lastImuReceivedAtMonotonicMs;

        private Snapshot(
                AccidentRecordSession.State state,
                int frameCount,
                long droppedFrames,
                long invalidSamples,
                boolean gpsKnown,
                boolean imuHealthKnown,
                long lastImuReceivedAtMonotonicMs) {
            this.state = state;
            this.frameCount = frameCount;
            this.droppedFrames = droppedFrames;
            this.invalidSamples = invalidSamples;
            this.gpsKnown = gpsKnown;
            this.imuHealthKnown = imuHealthKnown;
            this.lastImuReceivedAtMonotonicMs = lastImuReceivedAtMonotonicMs;
        }
    }

    private final AccidentRecordSession session;
    private AccidentImuSampleParser.Status status;
    private AccidentImuSampleParser.Health imuSummary;
    private AccidentRecordSession.GpsFix latestGps;
    private boolean bleConnected;
    private long blePacketsReceived;
    private long blePacketsLost;
    private boolean powerHealthy;
    private long lastImuReceivedAtMonotonicMs = -1L;

    public AccidentRecordCoordinator() {
        this(new AccidentRecordSession());
    }

    public AccidentRecordCoordinator(AccidentRecordSession.Config config) {
        this(new AccidentRecordSession(config));
    }

    AccidentRecordCoordinator(AccidentRecordSession session) {
        if (session == null) throw new IllegalArgumentException("session is required");
        this.session = session;
    }

    /** Begin a new user-confirmed record session. */
    public AccidentRecordSession.State begin(
            long atMonotonicMs,
            AccidentImuSampleParser.Status initialStatus,
            boolean initialBleConnected,
            long initialBlePacketsReceived,
            long initialBlePacketsLost,
            boolean initialPowerHealthy,
            AccidentRecordSession.GpsFix initialGps) {
        if (initialStatus == null) throw new IllegalArgumentException("IMU status is required");
        if (atMonotonicMs < 0 || initialBlePacketsReceived < 0 || initialBlePacketsLost < 0) {
            throw new IllegalArgumentException("invalid session start values");
        }
        status = initialStatus;
        imuSummary = null;
        latestGps = initialGps;
        bleConnected = initialBleConnected;
        blePacketsReceived = initialBlePacketsReceived;
        blePacketsLost = initialBlePacketsLost;
        powerHealthy = initialPowerHealthy;
        lastImuReceivedAtMonotonicMs = -1L;
        return session.begin(atMonotonicMs, buildHealth(atMonotonicMs));
    }

    /** Stop recording while retaining the bounded in-memory snapshot. */
    public void stop() {
        session.stop();
    }

    /** Disable and clear all frames and health state. */
    public void disable() {
        session.disable();
        status = null;
        imuSummary = null;
        latestGps = null;
        bleConnected = false;
        blePacketsReceived = 0L;
        blePacketsLost = 0L;
        powerHealthy = false;
        lastImuReceivedAtMonotonicMs = -1L;
    }

    /** Receive a legacy/versioned firmware status frame from BLE. */
    public AccidentRecordSession.State onStatus(
            AccidentImuSampleParser.Status nextStatus,
            boolean nextBleConnected,
            long nextBlePacketsReceived,
            long nextBlePacketsLost,
            boolean nextPowerHealthy,
            long observedAtMonotonicMs) {
        if (nextStatus == null || observedAtMonotonicMs < 0 ||
                nextBlePacketsReceived < 0 || nextBlePacketsLost < 0) {
            throw new IllegalArgumentException("invalid IMU status update");
        }
        status = nextStatus;
        bleConnected = nextBleConnected;
        blePacketsReceived = nextBlePacketsReceived;
        blePacketsLost = nextBlePacketsLost;
        powerHealthy = nextPowerHealthy;
        if (session.state() == AccidentRecordSession.State.DISABLED ||
                session.state() == AccidentRecordSession.State.STOPPED) {
            return session.state();
        }
        return session.updateHealth(buildHealth(observedAtMonotonicMs));
    }

    /** Receive the optional versioned firmware health summary from BLE. */
    public AccidentRecordSession.State onHealth(
            AccidentImuSampleParser.Health nextSummary,
            long observedAtMonotonicMs) {
        if (nextSummary == null || observedAtMonotonicMs < 0) {
            throw new IllegalArgumentException("invalid IMU health update");
        }
        imuSummary = nextSummary;
        if (session.state() == AccidentRecordSession.State.DISABLED ||
                session.state() == AccidentRecordSession.State.STOPPED) {
            return session.state();
        }
        return session.updateHealth(buildHealth(observedAtMonotonicMs));
    }

    /** Receive a phone GPS fix; invalid fixes remain explicit unknown data. */
    public AccidentRecordSession.State onGps(AccidentRecordSession.GpsFix fix) {
        if (fix == null) throw new IllegalArgumentException("GPS fix is required");
        latestGps = fix;
        return session.onGps(fix);
    }

    /** Receive one parsed BLE IMU sample. */
    public AccidentRecordSession.State onImu(AccidentImuSampleParser.Sample sample) {
        if (sample == null) throw new IllegalArgumentException("IMU sample is required");
        lastImuReceivedAtMonotonicMs = sample.receivedAtMonotonicMs;
        return session.onImu(sample);
    }

    /** Mark a missing BLE sample without inventing a sensor value. */
    public AccidentRecordSession.State tick(long nowMonotonicMs) {
        return session.tick(nowMonotonicMs);
    }

    public AccidentRecordSession.State state() {
        return session.state();
    }

    public AccidentRecordSession.Health health() {
        return session.health();
    }

    public List<AccidentRecordSession.Frame> snapshot() {
        return session.snapshot();
    }

    public Snapshot snapshotSummary() {
        AccidentRecordSession.Health health = session.health();
        return new Snapshot(
                session.state(),
                session.snapshot().size(),
                session.droppedFrames(),
                session.invalidSamples(),
                latestGps != null && latestGps.valid,
                health != null && health.imuSummary != null,
                lastImuReceivedAtMonotonicMs);
    }

    /** P1 has no notification side effect. */
    public boolean canNotify() {
        return false;
    }

    private AccidentRecordSession.Health buildHealth(long observedAtMonotonicMs) {
        if (status == null) return AccidentRecordSession.Health.unavailable(observedAtMonotonicMs);
        boolean gpsValid = latestGps != null && latestGps.valid;
        double gpsAccuracy = latestGps == null ? 0.0 : latestGps.accuracyM;
        AccidentRecordSession.Health health = AccidentRecordSession.Health.fromImuStatus(
                status,
                bleConnected,
                blePacketsReceived,
                blePacketsLost,
                powerHealthy,
                gpsValid,
                gpsAccuracy,
                observedAtMonotonicMs);
        return imuSummary == null ? health : health.withImuSummary(imuSummary, observedAtMonotonicMs);
    }
}
