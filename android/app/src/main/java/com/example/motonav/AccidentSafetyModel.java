package com.example.motonav;

/**
 * Pure JVM model for the first, record-only stage of suspected motorcycle
 * incident detection.
 *
 * <p>This class deliberately has no Android, BLE, contacts, SMS, or phone
 * dependencies. It combines a trusted riding sample, an impact or jerk, a
 * rotation/tilt change, and a period of stillness before it starts a local
 * confirmation countdown. A completed countdown only creates an
 * {@link AccidentEvent}; it never contacts anybody.</p>
 *
 * <p>The thresholds are conservative starting points for replay tests. They
 * are not safety-certified values and must be calibrated with measured data
 * for each installation before any notification path is considered.</p>
 */
public final class AccidentSafetyModel {
    public static final double STANDARD_GRAVITY_MPS2 = 9.80665;

    private AccidentSafetyModel() {}

    public enum State {
        DISABLED,
        ARMED,
        CANDIDATE,
        COUNTDOWN,
        RECORDED,
        CANCELLED,
        UNAVAILABLE
    }

    /** Tunable detector values kept together so replay tests can be explicit. */
    public static final class Config {
        public final double minimumRideSpeedKmh;
        public final double maximumLocationAccuracyM;
        public final double impactAccelerationMps2;
        public final double impactJerkMps3;
        public final double impactGyroDegPerSec;
        public final double impactTiltDeg;
        public final double stillSpeedKmh;
        public final double stillGyroDegPerSec;
        public final long rideEvidenceWindowMs;
        public final long candidateWindowMs;
        public final long stillnessDurationMs;
        public final long countdownMs;
        public final long maximumSampleGapMs;

        public Config(
                double minimumRideSpeedKmh,
                double maximumLocationAccuracyM,
                double impactAccelerationMps2,
                double impactJerkMps3,
                double impactGyroDegPerSec,
                double impactTiltDeg,
                double stillSpeedKmh,
                double stillGyroDegPerSec,
                long rideEvidenceWindowMs,
                long candidateWindowMs,
                long stillnessDurationMs,
                long countdownMs,
                long maximumSampleGapMs) {
            if (!finiteAndNonNegative(minimumRideSpeedKmh) ||
                    !finiteAndPositive(maximumLocationAccuracyM) ||
                    !finiteAndPositive(impactAccelerationMps2) ||
                    !finiteAndPositive(impactJerkMps3) ||
                    !finiteAndPositive(impactGyroDegPerSec) ||
                    !finiteAndPositive(impactTiltDeg) ||
                    !finiteAndNonNegative(stillSpeedKmh) ||
                    !finiteAndPositive(stillGyroDegPerSec) ||
                    rideEvidenceWindowMs <= 0 || candidateWindowMs <= 0 ||
                    stillnessDurationMs <= 0 || countdownMs <= 0 ||
                    maximumSampleGapMs <= 0) {
                throw new IllegalArgumentException("invalid accident detector configuration");
            }
            this.minimumRideSpeedKmh = minimumRideSpeedKmh;
            this.maximumLocationAccuracyM = maximumLocationAccuracyM;
            this.impactAccelerationMps2 = impactAccelerationMps2;
            this.impactJerkMps3 = impactJerkMps3;
            this.impactGyroDegPerSec = impactGyroDegPerSec;
            this.impactTiltDeg = impactTiltDeg;
            this.stillSpeedKmh = stillSpeedKmh;
            this.stillGyroDegPerSec = stillGyroDegPerSec;
            this.rideEvidenceWindowMs = rideEvidenceWindowMs;
            this.candidateWindowMs = candidateWindowMs;
            this.stillnessDurationMs = stillnessDurationMs;
            this.countdownMs = countdownMs;
            this.maximumSampleGapMs = maximumSampleGapMs;
        }

        /** Starting values for replay only; not a production safety threshold set. */
        public static Config defaults() {
            return new Config(
                    12.0,
                    100.0,
                    25.0,
                    80.0,
                    180.0,
                    55.0,
                    3.0,
                    45.0,
                    10_000L,
                    3_000L,
                    1_000L,
                    20_000L,
                    1_000L);
        }
    }

    /** One synchronized IMU/GPS/link-health sample. Acceleration is m/s^2. */
    public static final class SensorSample {
        public final long monotonicMs;
        public final double accelerationXMps2;
        public final double accelerationYMps2;
        public final double accelerationZMps2;
        public final double gyroXDegPerSec;
        public final double gyroYDegPerSec;
        public final double gyroZDegPerSec;
        public final double speedKmh;
        public final double locationAccuracyM;
        public final double tiltDeg;
        public final boolean locationValid;
        public final boolean imuHealthy;
        public final boolean bleConnected;
        public final boolean powerHealthy;
        public final boolean sensorSaturated;

        public SensorSample(
                long monotonicMs,
                double accelerationXMps2,
                double accelerationYMps2,
                double accelerationZMps2,
                double gyroXDegPerSec,
                double gyroYDegPerSec,
                double gyroZDegPerSec,
                double speedKmh,
                double locationAccuracyM,
                double tiltDeg,
                boolean locationValid,
                boolean imuHealthy,
                boolean bleConnected,
                boolean powerHealthy,
                boolean sensorSaturated) {
            this.monotonicMs = monotonicMs;
            this.accelerationXMps2 = accelerationXMps2;
            this.accelerationYMps2 = accelerationYMps2;
            this.accelerationZMps2 = accelerationZMps2;
            this.gyroXDegPerSec = gyroXDegPerSec;
            this.gyroYDegPerSec = gyroYDegPerSec;
            this.gyroZDegPerSec = gyroZDegPerSec;
            this.speedKmh = speedKmh;
            this.locationAccuracyM = locationAccuracyM;
            this.tiltDeg = tiltDeg;
            this.locationValid = locationValid;
            this.imuHealthy = imuHealthy;
            this.bleConnected = bleConnected;
            this.powerHealthy = powerHealthy;
            this.sensorSaturated = sensorSaturated;
        }

        /** A stationary, healthy sample with gravity on the Z axis. */
        public static SensorSample healthyRest(long monotonicMs, double speedKmh, double accuracyM) {
            return new SensorSample(
                    monotonicMs,
                    0.0,
                    0.0,
                    STANDARD_GRAVITY_MPS2,
                    0.0,
                    0.0,
                    0.0,
                    speedKmh,
                    accuracyM,
                    0.0,
                    true,
                    true,
                    true,
                    true,
                    false);
        }

        /** A healthy replay sample with vector magnitudes on the X axis. */
        public static SensorSample healthy(
                long monotonicMs,
                double accelerationMps2,
                double gyroDegPerSec,
                double speedKmh,
                double accuracyM,
                double tiltDeg) {
            return new SensorSample(
                    monotonicMs,
                    accelerationMps2,
                    0.0,
                    0.0,
                    gyroDegPerSec,
                    0.0,
                    0.0,
                    speedKmh,
                    accuracyM,
                    tiltDeg,
                    true,
                    true,
                    true,
                    true,
                    false);
        }

        public double accelerationMagnitudeMps2() {
            return magnitude(accelerationXMps2, accelerationYMps2, accelerationZMps2);
        }

        public double gyroMagnitudeDegPerSec() {
            return magnitude(gyroXDegPerSec, gyroYDegPerSec, gyroZDegPerSec);
        }

        private static double magnitude(double x, double y, double z) {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /** Locally recorded candidate details. It contains no contact or message data. */
    public static final class AccidentEvent {
        public final long eventId;
        public final long occurredAtMonotonicMs;
        public final long recordedAtMonotonicMs;
        public final double peakAccelerationMps2;
        public final double peakJerkMps3;
        public final double peakGyroDegPerSec;
        public final double peakSpeedKmh;
        public final double peakTiltDeg;
        public final double locationAccuracyM;
        public final String trigger;

        private AccidentEvent(
                long eventId,
                long occurredAtMonotonicMs,
                long recordedAtMonotonicMs,
                double peakAccelerationMps2,
                double peakJerkMps3,
                double peakGyroDegPerSec,
                double peakSpeedKmh,
                double peakTiltDeg,
                double locationAccuracyM,
                String trigger) {
            this.eventId = eventId;
            this.occurredAtMonotonicMs = occurredAtMonotonicMs;
            this.recordedAtMonotonicMs = recordedAtMonotonicMs;
            this.peakAccelerationMps2 = peakAccelerationMps2;
            this.peakJerkMps3 = peakJerkMps3;
            this.peakGyroDegPerSec = peakGyroDegPerSec;
            this.peakSpeedKmh = peakSpeedKmh;
            this.peakTiltDeg = peakTiltDeg;
            this.locationAccuracyM = locationAccuracyM;
            this.trigger = trigger;
        }
    }

    /**
     * Stateful detector. It intentionally exposes no notification callback;
     * a later integration layer must make a separate, reviewed decision.
     */
    public static final class Detector {
        private final Config config;
        private State state = State.DISABLED;
        private long lastSampleAt = -1;
        private long lastMovingAt = -1;
        private long candidateStartedAt = -1;
        private long stillSince = -1;
        private long countdownUntil = -1;
        private SensorSample previousSample;
        private double peakAcceleration;
        private double peakJerk;
        private double peakGyro;
        private double peakSpeed;
        private double peakTilt;
        private double candidateAccuracy;
        private long eventSequence;
        private AccidentEvent lastRecordedEvent;

        public Detector() {
            this(Config.defaults());
        }

        public Detector(Config config) {
            if (config == null) throw new IllegalArgumentException("config is required");
            this.config = config;
        }

        public State state() {
            return state;
        }

        public AccidentEvent lastRecordedEvent() {
            return lastRecordedEvent;
        }

        public long countdownRemainingMs(long nowMs) {
            if (state != State.COUNTDOWN || countdownUntil < 0 || nowMs >= countdownUntil) return 0;
            return countdownUntil - nowMs;
        }

        public void arm() {
            clearSession();
            state = State.ARMED;
        }

        public void disarm() {
            clearSession();
            state = State.DISABLED;
        }

        /** Cancel a visible confirmation countdown without recording an event. */
        public void cancel() {
            if (state == State.COUNTDOWN || state == State.CANDIDATE) {
                clearCandidate();
                state = State.CANCELLED;
            }
        }

        /**
         * Feed a monotonic sample. Invalid or repeated timestamps are ignored;
         * unhealthy samples make the detector unavailable and never trigger it.
         */
        public State onSample(SensorSample sample) {
            if (sample == null || state == State.DISABLED || state == State.CANCELLED ||
                    state == State.RECORDED || state == State.UNAVAILABLE) return state;
            if (!timestampIsUsable(sample.monotonicMs)) {
                clearSessionData();
                state = State.UNAVAILABLE;
                return state;
            }
            if (!isHealthy(sample)) {
                clearSessionData();
                state = State.UNAVAILABLE;
                return state;
            }

            long previousAt = lastSampleAt;
            lastSampleAt = sample.monotonicMs;
            double acceleration = sample.accelerationMagnitudeMps2();
            double gyro = sample.gyroMagnitudeDegPerSec();
            double jerk = 0.0;
            if (previousSample != null) {
                long deltaMs = sample.monotonicMs - previousSample.monotonicMs;
                if (deltaMs > 0) {
                    jerk = Math.abs(acceleration - previousSample.accelerationMagnitudeMps2()) /
                            (deltaMs / 1000.0);
                }
            }
            updatePeaks(sample, acceleration, jerk, gyro);

            // Evaluate the impact against evidence from an earlier sample. A
            // high speed reported on the impact sample alone is not proof that
            // the vehicle was moving before the event.
            boolean hadRecentRideEvidence = hasRecentRideEvidence(sample.monotonicMs);
            if (sample.speedKmh >= config.minimumRideSpeedKmh) lastMovingAt = sample.monotonicMs;
            if (state == State.ARMED) {
                if (hadRecentRideEvidence && hasDynamicEvidence(sample, acceleration, jerk, gyro)) {
                    candidateStartedAt = sample.monotonicMs;
                    candidateAccuracy = sample.locationAccuracyM;
                    stillSince = -1;
                    state = State.CANDIDATE;
                }
            } else if (state == State.CANDIDATE) {
                if (sample.monotonicMs - candidateStartedAt > config.candidateWindowMs) {
                    clearCandidate();
                    state = State.ARMED;
                } else if (isStill(sample, gyro)) {
                    if (stillSince < 0) stillSince = sample.monotonicMs;
                    if (sample.monotonicMs - stillSince >= config.stillnessDurationMs) {
                        countdownUntil = sample.monotonicMs + config.countdownMs;
                        state = State.COUNTDOWN;
                    }
                } else {
                    stillSince = -1;
                }
            } else if (state == State.COUNTDOWN) {
                tick(sample.monotonicMs);
            }
            previousSample = sample;
            return state;
        }

        /** Advance a countdown or expire an old candidate without a new sample. */
        public State tick(long nowMs) {
            if (nowMs < 0 || (lastSampleAt >= 0 && nowMs < lastSampleAt)) return state;
            if (state == State.CANDIDATE && candidateStartedAt >= 0 &&
                    nowMs - candidateStartedAt > config.candidateWindowMs) {
                clearCandidate();
                state = State.ARMED;
            }
            if (state == State.COUNTDOWN && countdownUntil >= 0 && nowMs >= countdownUntil) {
                lastRecordedEvent = new AccidentEvent(
                        ++eventSequence,
                        candidateStartedAt,
                        countdownUntil,
                        peakAcceleration,
                        peakJerk,
                        peakGyro,
                        peakSpeed,
                        peakTilt,
                        candidateAccuracy,
                        "IMPACT_ROTATION_STILLNESS");
                clearCandidate();
                state = State.RECORDED;
            }
            return state;
        }

        private boolean timestampIsUsable(long timestampMs) {
            if (timestampMs < 0 || (lastSampleAt >= 0 && timestampMs <= lastSampleAt)) return false;
            return lastSampleAt < 0 || timestampMs - lastSampleAt <= config.maximumSampleGapMs;
        }

        private boolean isHealthy(SensorSample sample) {
            return sample.imuHealthy && sample.bleConnected && sample.powerHealthy &&
                    sample.locationValid && finiteAndNonNegative(sample.locationAccuracyM) &&
                    sample.locationAccuracyM <= config.maximumLocationAccuracyM &&
                    finiteAndNonNegative(sample.speedKmh) && sample.speedKmh <= 300.0 &&
                    Double.isFinite(sample.tiltDeg) && Math.abs(sample.tiltDeg) <= 180.0 &&
                    finiteAndNonNegative(sample.accelerationMagnitudeMps2()) &&
                    finiteAndNonNegative(sample.gyroMagnitudeDegPerSec()) &&
                    !sample.sensorSaturated;
        }

        private boolean hasRecentRideEvidence(long at) {
            return lastMovingAt >= 0 && at >= lastMovingAt &&
                    at - lastMovingAt <= config.rideEvidenceWindowMs;
        }

        private boolean hasDynamicEvidence(SensorSample sample, double acceleration, double jerk, double gyro) {
            boolean shock = acceleration >= config.impactAccelerationMps2 || jerk >= config.impactJerkMps3;
            double tiltDelta = previousSample == null ? 0.0 :
                    Math.abs(sample.tiltDeg - previousSample.tiltDeg);
            boolean posture = Math.abs(sample.tiltDeg) >= config.impactTiltDeg || tiltDelta >= config.impactTiltDeg;
            return shock && (gyro >= config.impactGyroDegPerSec || posture);
        }

        private boolean isStill(SensorSample sample, double gyro) {
            return sample.speedKmh <= config.stillSpeedKmh && gyro <= config.stillGyroDegPerSec;
        }

        private void updatePeaks(SensorSample sample, double acceleration, double jerk, double gyro) {
            peakAcceleration = Math.max(peakAcceleration, acceleration);
            peakJerk = Math.max(peakJerk, jerk);
            peakGyro = Math.max(peakGyro, gyro);
            peakSpeed = Math.max(peakSpeed, sample.speedKmh);
            peakTilt = Math.max(peakTilt, Math.abs(sample.tiltDeg));
        }

        private void clearSession() {
            clearSessionData();
            lastRecordedEvent = null;
            eventSequence = 0;
        }

        private void clearSessionData() {
            lastSampleAt = -1;
            lastMovingAt = -1;
            previousSample = null;
            clearCandidate();
        }

        private void clearCandidate() {
            candidateStartedAt = -1;
            stillSince = -1;
            countdownUntil = -1;
            peakAcceleration = 0.0;
            peakJerk = 0.0;
            peakGyro = 0.0;
            peakSpeed = 0.0;
            peakTilt = 0.0;
            candidateAccuracy = 0.0;
        }
    }

    private static boolean finiteAndPositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean finiteAndNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
