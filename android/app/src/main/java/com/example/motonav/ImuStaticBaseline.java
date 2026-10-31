package com.example.motonav;

import java.util.Locale;

/**
 * Record-only collection of one stationary IMU orientation.
 *
 * <p>This class measures a mounted sensor; it never applies a bias, changes
 * the detector, or claims that the device is safe for automatic notification.
 * A caller must explicitly review each completed result before using it for
 * any later calibration work.</p>
 */
public final class ImuStaticBaseline {
    public enum Pose {
        SCREEN_UP("屏幕朝上"),
        SCREEN_DOWN("屏幕朝下"),
        SCREEN_LEFT("屏幕朝左"),
        SCREEN_RIGHT("屏幕朝右");

        public final String label;

        Pose(String label) {
            this.label = label;
        }
    }

    public enum State { IDLE, COLLECTING, COMPLETE, FAILED, CANCELLED }

    public static final long REQUIRED_DURATION_MS = 60_000L;
    public static final long COLLECTION_TIMEOUT_MS = 90_000L;
    public static final int MINIMUM_SAMPLES = 400;
    public static final long MAX_SAMPLE_GAP_MS = 750L;

    private static final double GRAVITY_MIN_MPS2 = 7.5;
    private static final double GRAVITY_MAX_MPS2 = 12.5;
    private static final double MAX_GYRO_MAGNITUDE_DPS = 30.0;
    private static final double MAX_ACCEL_AXIS_STD_MPS2 = 0.35;
    private static final double MAX_GYRO_AXIS_STD_DPS = 1.5;
    private static final double MAX_ACCEL_MAG_STD_MPS2 = 0.35;
    private static final double MAX_GYRO_MAG_STD_DPS = 1.5;

    private State state = State.IDLE;
    private Pose pose;
    private long startedAt = -1L;
    private long firstBoardAt = -1L;
    private long lastBoardAt = -1L;
    private long firstReceivedAt = -1L;
    private long lastReceivedAt = -1L;
    private long boardIntervalMin = Long.MAX_VALUE;
    private long boardIntervalMax = 0L;
    private long receivedIntervalMin = Long.MAX_VALUE;
    private long receivedIntervalMax = 0L;
    private int acceptedSamples;
    private int rejectedSamples;
    private String reason = "未开始";

    private AccidentImuSampleParser.Status status;
    private long statusReceivedAt = -1L;
    private long statusPreviousSamples;
    private long statusPreviousBoardMillis;
    private long statusProgressReceivedAt = -1L;
    private boolean statusProgressSet;
    private boolean statusSequenceSafe;
    private AccidentImuSampleParser.Health health;
    private long healthReceivedAt = -1L;
    private long healthBaselineSamples;
    private long healthBaselineReadErrors;
    private long healthBaselineSampleGaps;
    private long healthBaselineGravityOutOfRange;
    private long healthBaselineAccelSaturated;
    private long healthBaselineGyroSaturated;
    private long healthPreviousSamples;
    private long healthProgressReceivedAt = -1L;
    private boolean healthBaselineSet;
    private boolean healthSequenceSafe;

    private final RunningStats ax = new RunningStats();
    private final RunningStats ay = new RunningStats();
    private final RunningStats az = new RunningStats();
    private final RunningStats gx = new RunningStats();
    private final RunningStats gy = new RunningStats();
    private final RunningStats gz = new RunningStats();
    private final RunningStats accelMagnitude = new RunningStats();
    private final RunningStats gyroMagnitude = new RunningStats();
    private final RunningStats temperature = new RunningStats();

    /** Begin a new explicit stationary capture. */
    public void start(Pose pose, long nowMs) {
        if (pose == null || nowMs < 0L) throw new IllegalArgumentException("pose/time required");
        this.pose = pose;
        this.startedAt = nowMs;
        this.state = State.COLLECTING;
        this.reason = "等待健康状态和静置样本";
        this.status = null;
        this.statusReceivedAt = -1L;
        this.statusProgressSet = false;
        this.statusSequenceSafe = true;
        this.statusProgressReceivedAt = -1L;
        this.statusPreviousSamples = this.statusPreviousBoardMillis = -1L;
        this.health = null;
        this.healthReceivedAt = -1L;
        this.healthBaselineSet = false;
        this.healthBaselineSamples = this.healthBaselineReadErrors = 0L;
        this.healthBaselineSampleGaps = this.healthBaselineGravityOutOfRange = 0L;
        this.healthBaselineAccelSaturated = this.healthBaselineGyroSaturated = 0L;
        this.healthPreviousSamples = -1L;
        this.healthProgressReceivedAt = -1L;
        this.healthSequenceSafe = true;
        this.firstBoardAt = this.lastBoardAt = -1L;
        this.firstReceivedAt = this.lastReceivedAt = -1L;
        this.boardIntervalMin = this.receivedIntervalMin = Long.MAX_VALUE;
        this.boardIntervalMax = this.receivedIntervalMax = 0L;
        this.acceptedSamples = this.rejectedSamples = 0;
        ax.clear(); ay.clear(); az.clear(); gx.clear(); gy.clear(); gz.clear();
        accelMagnitude.clear(); gyroMagnitude.clear(); temperature.clear();
    }

    /** Status is required before capture; it is never synthesized from samples. */
    public void updateStatus(AccidentImuSampleParser.Status status) {
        updateStatus(status, startedAt);
    }

    /**
     * Update status with the phone's monotonic receive timestamp. The
     * timestamped overload is required for a real capture; the one-argument
     * overload exists only for replay callers that do not model BLE timing.
     */
    public void updateStatus(AccidentImuSampleParser.Status status, long receivedAtMs) {
        if (state != State.COLLECTING) return;
        this.status = status;
        this.statusReceivedAt = receivedAtMs;
        if (status == null || !status.usable() || !status.streamEnabled || receivedAtMs < startedAt) {
            reason = "IMU 状态不可用";
            statusSequenceSafe = false;
        } else if (!statusProgressSet) {
            statusProgressSet = true;
            statusPreviousSamples = status.samples;
            statusPreviousBoardMillis = status.lastBoardMillis;
            statusProgressReceivedAt = receivedAtMs;
        } else if (status.samples < statusPreviousSamples ||
                status.lastBoardMillis < statusPreviousBoardMillis) {
            reason = "IMU 状态计数倒退";
            statusSequenceSafe = false;
        } else {
            if (status.samples > statusPreviousSamples &&
                    status.lastBoardMillis > statusPreviousBoardMillis) {
                statusProgressReceivedAt = receivedAtMs;
            }
            statusPreviousSamples = status.samples;
            statusPreviousBoardMillis = status.lastBoardMillis;
        }
        maybeFinish();
    }

    /** Health must be refreshed by the caller while the capture is running. */
    public void updateHealth(AccidentImuSampleParser.Health health) {
        if (state != State.COLLECTING) return;
        this.health = health;
        this.healthReceivedAt = health == null ? -1L : health.receivedAtMonotonicMs;
        if (!healthUsable(health)) {
            // The first #IMU? response can legitimately arrive before the
            // sensor has produced a ring sample. Wait for the first usable
            // summary; once a baseline exists, any later health failure is
            // latched and invalidates the capture.
            if (!healthBaselineSet && acceptedSamples == 0) {
                reason = "等待首个有效 IMU 健康摘要";
                maybeFinish();
                return;
            }
            reason = "IMU 健康摘要不可用";
            healthSequenceSafe = false;
        } else if (!healthBaselineSet) {
            healthBaselineSamples = health.samples;
            healthBaselineReadErrors = health.readErrors;
            healthBaselineSampleGaps = health.sampleGaps;
            healthBaselineGravityOutOfRange = health.gravityOutOfRange;
            healthBaselineAccelSaturated = health.accelSaturated;
            healthBaselineGyroSaturated = health.gyroSaturated;
            healthPreviousSamples = health.samples;
            healthProgressReceivedAt = health.receivedAtMonotonicMs;
            healthBaselineSet = true;
        } else if (!healthCountersSafe(health)) {
            reason = "IMU 健康计数在采集中增加";
            healthSequenceSafe = false;
        } else {
            if (health.samples < healthPreviousSamples) {
                reason = "IMU 健康样本计数倒退";
                healthSequenceSafe = false;
            } else if (health.samples > healthPreviousSamples) {
                healthProgressReceivedAt = health.receivedAtMonotonicMs;
            }
            healthPreviousSamples = health.samples;
        }
        maybeFinish();
    }

    /** Accept one sample, or fail the capture when continuity or motion is unsafe. */
    public boolean onSample(AccidentImuSampleParser.Sample sample) {
        if (state != State.COLLECTING || sample == null) return false;
        if (sample.receivedAtMonotonicMs < startedAt) return reject("样本早于采集开始");
        if (acceptedSamples == 0 && (status == null || health == null ||
                !healthUsable(health) || !healthBaselineSet)) {
            reason = "等待本次采集的新状态与健康摘要";
            return false;
        }
        if (status == null || !status.usable() || !status.streamEnabled || !statusSequenceSafe) {
            return reject("IMU 状态不可用");
        }
        if (statusReceivedAt < startedAt || statusReceivedAt > sample.receivedAtMonotonicMs ||
                sample.receivedAtMonotonicMs - statusReceivedAt > 2_500L) {
            return reject("IMU 状态摘要过期");
        }
        if (statusProgressReceivedAt < startedAt ||
                sample.receivedAtMonotonicMs - statusProgressReceivedAt > 2_500L) {
            return reject("IMU 状态样本计数未推进");
        }
        if (!healthUsable(health) || !healthBaselineSet || !healthSequenceSafe ||
                !healthCountersSafe(health)) {
            return reject("IMU 健康摘要过期");
        }
        if (healthReceivedAt < startedAt || healthReceivedAt > sample.receivedAtMonotonicMs ||
                sample.receivedAtMonotonicMs - healthReceivedAt > 2_500L) {
            return reject("IMU 健康摘要过期");
        }
        if (healthProgressReceivedAt < startedAt ||
                sample.receivedAtMonotonicMs - healthProgressReceivedAt > 2_500L) {
            return reject("IMU 健康样本计数未推进");
        }
        if (!sample.finite()) return reject("样本不是有限数值");
        if (firstBoardAt >= 0L) {
            long boardDelta = sample.boardMillis - lastBoardAt;
            long receivedDelta = sample.receivedAtMonotonicMs - lastReceivedAt;
            if (boardDelta <= 0L || receivedDelta <= 0L) return reject("样本时间倒退或重复");
            if (boardDelta > MAX_SAMPLE_GAP_MS || receivedDelta > MAX_SAMPLE_GAP_MS) {
                return reject("样本时间间隔过大");
            }
            boardIntervalMin = Math.min(boardIntervalMin, boardDelta);
            boardIntervalMax = Math.max(boardIntervalMax, boardDelta);
            receivedIntervalMin = Math.min(receivedIntervalMin, receivedDelta);
            receivedIntervalMax = Math.max(receivedIntervalMax, receivedDelta);
        } else {
            firstBoardAt = sample.boardMillis;
            firstReceivedAt = sample.receivedAtMonotonicMs;
        }
        double a = sample.accelerationMagnitudeMps2();
        double g = sample.gyroMagnitudeDegPerSec();
        if (a < GRAVITY_MIN_MPS2 || a > GRAVITY_MAX_MPS2 || g > MAX_GYRO_MAGNITUDE_DPS) {
            return reject("姿态或运动不稳定");
        }
        lastBoardAt = sample.boardMillis;
        lastReceivedAt = sample.receivedAtMonotonicMs;
        acceptedSamples++;
        ax.add(sample.accelerationXMps2); ay.add(sample.accelerationYMps2); az.add(sample.accelerationZMps2);
        gx.add(sample.gyroXDegPerSec); gy.add(sample.gyroYDegPerSec); gz.add(sample.gyroZDegPerSec);
        accelMagnitude.add(a); gyroMagnitude.add(g); temperature.add(sample.temperatureC);
        reason = "采集中（仍需人工复核）";
        maybeFinish();
        return true;
    }

    /** Timeout/cancel check driven by the phone's monotonic clock. */
    public State tick(long nowMs) {
        if (state != State.COLLECTING || nowMs < 0L) return state;
        if (nowMs < startedAt) return state;
        if (firstReceivedAt >= 0L && nowMs - lastReceivedAt > MAX_SAMPLE_GAP_MS) {
            state = State.FAILED;
            reason = "样本接收中断：间隔过大";
        } else {
            maybeFinish();
        }
        if (state == State.COLLECTING && nowMs - startedAt >= COLLECTION_TIMEOUT_MS) {
            state = State.FAILED;
            reason = acceptedSamples == 0 ? "超时：没有有效样本" : "超时：有效样本或静置时长不足";
        }
        return state;
    }

    public void cancel() {
        if (state == State.COLLECTING) {
            state = State.CANCELLED;
            reason = "用户取消";
        }
    }

    public State state() { return state; }
    public Pose pose() { return pose; }
    public String reason() { return reason; }
    public int acceptedSamples() { return acceptedSamples; }
    public int rejectedSamples() { return rejectedSamples; }

    /** Snapshot for UI/export. complete means collected; it is not a safety approval. */
    public Result result() {
        return new Result(state, pose, reason, acceptedSamples, rejectedSamples, startedAt,
                firstBoardAt, lastBoardAt, firstReceivedAt, lastReceivedAt,
                boardIntervalMin == Long.MAX_VALUE ? 0L : boardIntervalMin, boardIntervalMax,
                receivedIntervalMin == Long.MAX_VALUE ? 0L : receivedIntervalMin, receivedIntervalMax,
                ax.snapshot(), ay.snapshot(), az.snapshot(), gx.snapshot(), gy.snapshot(), gz.snapshot(),
                accelMagnitude.snapshot(), gyroMagnitude.snapshot(), temperature.snapshot());
    }

    private boolean readyByTimeAndCount() {
        return acceptedSamples >= MINIMUM_SAMPLES && firstBoardAt >= 0L &&
                lastBoardAt - firstBoardAt >= REQUIRED_DURATION_MS &&
                firstReceivedAt >= 0L && lastReceivedAt - firstReceivedAt >= REQUIRED_DURATION_MS;
    }

    private void maybeFinish() {
        if (state == State.COLLECTING && readyByTimeAndCount() &&
                healthUsable(health) && healthBaselineSet && healthCountersSafe(health) &&
                healthSequenceSafe &&
                status != null && status.usable() && status.streamEnabled &&
                statusSequenceSafe &&
                statusProgressReceivedAt >= lastReceivedAt && healthProgressReceivedAt >= lastReceivedAt &&
                healthReceivedAt >= lastReceivedAt && statusReceivedAt >= lastReceivedAt) {
            finish();
        }
    }

    private void finish() {
        Result snapshot = result();
        if (snapshot.accelerationX.stdDev > MAX_ACCEL_AXIS_STD_MPS2 ||
                snapshot.accelerationY.stdDev > MAX_ACCEL_AXIS_STD_MPS2 ||
                snapshot.accelerationZ.stdDev > MAX_ACCEL_AXIS_STD_MPS2 ||
                snapshot.gyroX.stdDev > MAX_GYRO_AXIS_STD_DPS ||
                snapshot.gyroY.stdDev > MAX_GYRO_AXIS_STD_DPS ||
                snapshot.gyroZ.stdDev > MAX_GYRO_AXIS_STD_DPS ||
                snapshot.accelerationMagnitude.stdDev > MAX_ACCEL_MAG_STD_MPS2 ||
                snapshot.gyroMagnitude.stdDev > MAX_GYRO_MAG_STD_DPS) {
            state = State.FAILED;
            reason = "静置方差过大：需要重新摆放并复测";
        } else {
            state = State.COMPLETE;
            reason = "采集完成，必须人工复核；未应用任何零偏";
        }
    }

    private boolean reject(String message) {
        rejectedSamples++;
        state = State.FAILED;
        reason = message;
        return false;
    }

    private static boolean healthUsable(AccidentImuSampleParser.Health health) {
        return health != null && health.usableForRecording();
    }

    private boolean healthCountersSafe(AccidentImuSampleParser.Health current) {
        return current != null && current.samples >= healthBaselineSamples &&
                current.readErrors == healthBaselineReadErrors &&
                current.sampleGaps == healthBaselineSampleGaps &&
                current.gravityOutOfRange == healthBaselineGravityOutOfRange &&
                current.accelSaturated == healthBaselineAccelSaturated &&
                current.gyroSaturated == healthBaselineGyroSaturated;
    }

    public static final class AxisStats {
        public final int count;
        public final double mean;
        public final double stdDev;
        public final double min;
        public final double max;

        private AxisStats(int count, double mean, double stdDev, double min, double max) {
            this.count = count; this.mean = mean; this.stdDev = stdDev; this.min = min; this.max = max;
        }
    }

    public static final class Result {
        public final State state;
        public final Pose pose;
        public final String reason;
        public final int acceptedSamples;
        public final int rejectedSamples;
        public final long startedAt;
        public final long firstBoardAt, lastBoardAt, firstReceivedAt, lastReceivedAt;
        public final long boardIntervalMin, boardIntervalMax, receivedIntervalMin, receivedIntervalMax;
        public final AxisStats accelerationX, accelerationY, accelerationZ;
        public final AxisStats gyroX, gyroY, gyroZ;
        public final AxisStats accelerationMagnitude, gyroMagnitude, temperature;

        private Result(State state, Pose pose, String reason, int acceptedSamples, int rejectedSamples,
                       long startedAt, long firstBoardAt, long lastBoardAt, long firstReceivedAt,
                       long lastReceivedAt, long boardIntervalMin, long boardIntervalMax,
                       long receivedIntervalMin, long receivedIntervalMax, AxisStats accelerationX,
                       AxisStats accelerationY, AxisStats accelerationZ, AxisStats gyroX,
                       AxisStats gyroY, AxisStats gyroZ, AxisStats accelerationMagnitude,
                       AxisStats gyroMagnitude, AxisStats temperature) {
            this.state = state; this.pose = pose; this.reason = reason;
            this.acceptedSamples = acceptedSamples; this.rejectedSamples = rejectedSamples;
            this.startedAt = startedAt; this.firstBoardAt = firstBoardAt; this.lastBoardAt = lastBoardAt;
            this.firstReceivedAt = firstReceivedAt; this.lastReceivedAt = lastReceivedAt;
            this.boardIntervalMin = boardIntervalMin; this.boardIntervalMax = boardIntervalMax;
            this.receivedIntervalMin = receivedIntervalMin; this.receivedIntervalMax = receivedIntervalMax;
            this.accelerationX = accelerationX; this.accelerationY = accelerationY; this.accelerationZ = accelerationZ;
            this.gyroX = gyroX; this.gyroY = gyroY; this.gyroZ = gyroZ;
            this.accelerationMagnitude = accelerationMagnitude; this.gyroMagnitude = gyroMagnitude;
            this.temperature = temperature;
        }

        public boolean completeForReview() { return state == State.COMPLETE; }
        public boolean safetyApproved() { return false; }

        @Override public String toString() {
            return String.format(Locale.US, "%s %s n=%d a=(%.3f,%.3f,%.3f) g=(%.3f,%.3f,%.3f) temp=%.2f",
                    pose == null ? "?" : pose.label, state, acceptedSamples,
                    accelerationX.mean, accelerationY.mean, accelerationZ.mean,
                    gyroX.mean, gyroY.mean, gyroZ.mean, temperature.mean);
        }
    }

    private static final class RunningStats {
        private int count;
        private double mean;
        private double m2;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        void clear() { count = 0; mean = m2 = 0.0; min = Double.POSITIVE_INFINITY; max = Double.NEGATIVE_INFINITY; }
        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
            min = Math.min(min, value); max = Math.max(max, value);
        }
        AxisStats snapshot() {
            return new AxisStats(count, count == 0 ? 0.0 : mean,
                    count == 0 ? 0.0 : Math.sqrt(Math.max(0.0, m2 / count)),
                    count == 0 ? 0.0 : min, count == 0 ? 0.0 : max);
        }
    }
}
