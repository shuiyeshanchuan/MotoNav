package com.example.motonav;

/** Pure JVM tests for the explicit, record-only static IMU capture. */
public final class ImuStaticBaselineTest {
    private static int passed;

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static AccidentImuSampleParser.Status status(long at, long sampleCount) {
        return (AccidentImuSampleParser.Status) AccidentImuSampleParser.parse(
                "IMU,STATUS,6B,1,1,1,1," + sampleCount + ",0," + (12345L + at), 0);
    }

    private static AccidentImuSampleParser.Health health(long receivedAt, long samples,
                                                          long sampleGaps, long gravityOut,
                                                          long accelSaturated, long gyroSaturated) {
        long gravityIn = samples - gravityOut;
        return (AccidentImuSampleParser.Health) AccidentImuSampleParser.parse(
                "IMU,HEALTH,1," + samples + ",0," + sampleGaps + "," + gravityIn + "," +
                        gravityOut + ",12," + accelSaturated + "," + gyroSaturated +
                        ",9,12,42,980,71", receivedAt);
    }

    private static AccidentImuSampleParser.Health emptyHealth(long receivedAt) {
        AccidentImuSampleParser.Health summary = (AccidentImuSampleParser.Health)
                AccidentImuSampleParser.parse("IMU,HEALTH,1,0,0,0,0,0,0,0,0,0,0,0,0,0", receivedAt);
        require(summary != null && !summary.usableForRecording(), "empty health fixture must be valid but unusable");
        return summary;
    }

    private static AccidentImuSampleParser.Sample sample(long boardAt, long receivedAt,
                                                          double ax, double ay, double az,
                                                          double gx, double gy, double gz,
                                                          double temp) {
        return (AccidentImuSampleParser.Sample) AccidentImuSampleParser.parse(
                "IMU," + boardAt + "," + ax + "," + ay + "," + az + "," + gx + "," + gy +
                        "," + gz + "," + temp, receivedAt);
    }

    private static void fresh(ImuStaticBaseline baseline, long at, long healthSamples) {
        baseline.updateStatus(status(at, healthSamples), at);
        baseline.updateHealth(health(at, healthSamples, 0, 0, 0, 0));
    }

    private static void collect(ImuStaticBaseline baseline, double ax, double ay, double az,
                                double gx, double gy, double gz) {
        for (int i = 0; i <= 600; i++) {
            long at = i * 100L;
            fresh(baseline, at, 42L + i);
            require(baseline.onSample(sample(at, at, ax, ay, az, gx, gy, gz, 30.0)),
                    "stable sample rejected at " + i);
            if (i < 399) require(baseline.state() == ImuStaticBaseline.State.COLLECTING,
                    "capture completed before 60 seconds");
        }
    }

    private static ImuStaticBaseline awaitingFinalEvidence() {
        ImuStaticBaseline baseline = new ImuStaticBaseline();
        baseline.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
        for (int i = 0; i <= 600; i++) {
            long at = i * 100L;
            if (i % 10 == 0 && i < 600) fresh(baseline, at, 42L + i);
            require(baseline.onSample(sample(at, at, 0, 0, 9.8, -7, 0, 0, 30)),
                    "capture setup rejected a stable sample");
        }
        require(baseline.acceptedSamples() == 601 &&
                        baseline.state() == ImuStaticBaseline.State.COLLECTING,
                "capture must await its final health evidence");
        return baseline;
    }

    private static void requireLatchedInvalid(ImuStaticBaseline baseline, String description) {
        require(!baseline.result().completeForReview(), description + " completed after restored summary");
        require(baseline.tick(60030) != ImuStaticBaseline.State.COMPLETE,
                description + " completed through tick after restored summary");
        require(!baseline.onSample(sample(60100, 60100, 0, 0, 9.8, -7, 0, 0, 30)) &&
                        baseline.state() == ImuStaticBaseline.State.FAILED,
                description + " did not stay latched until the next sample");
        require(!baseline.result().completeForReview(), description + " produced a completed result");
    }

    public static void main(String[] args) {
        test("all four explicit screen poses complete after both clocks reach 60 seconds", () -> {
            double[][] poses = {{0, 0, 9.8}, {0, 0, -9.8}, {0, -9.8, 0}, {0, 9.8, 0}};
            for (int i = 0; i < poses.length; i++) {
                double[] p = poses[i];
                ImuStaticBaseline baseline = new ImuStaticBaseline();
                baseline.start(ImuStaticBaseline.Pose.values()[i], 0);
                collect(baseline, p[0], p[1], p[2], -7.1, 0.2, 0.1);
                require(baseline.state() == ImuStaticBaseline.State.COMPLETE,
                        "pose did not complete: " + baseline.reason());
                require(baseline.result().acceptedSamples == 601, "sample count changed");
                require(baseline.result().completeForReview() && !baseline.result().safetyApproved(),
                        "record result became a safety approval");
            }
        });

        test("raw means, population deviation, extrema, vectors, temperature, and gyro bias are retained", () -> {
            ImuStaticBaseline baseline = new ImuStaticBaseline();
            baseline.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i <= 600; i++) {
                long at = i * 100L;
                fresh(baseline, at, 42L + i);
                double ax = (i & 1) == 0 ? 0.1 : 0.2;
                double temp = (i & 1) == 0 ? 30.0 : 32.0;
                require(baseline.onSample(sample(at, at, ax, 0, 9.8, -7.1, 0.2, 0.1, temp)),
                        "stat sample rejected");
            }
            ImuStaticBaseline.Result r = baseline.result();
            require(r.state == ImuStaticBaseline.State.COMPLETE, "stat capture incomplete");
            double expectedAx = (301.0 * 0.1 + 300.0 * 0.2) / 601.0;
            double expectedAxVar = (301.0 * Math.pow(0.1 - expectedAx, 2) +
                    300.0 * Math.pow(0.2 - expectedAx, 2)) / 601.0;
            require(Math.abs(r.accelerationX.mean - expectedAx) < 1e-12, "wrong acceleration mean");
            require(Math.abs(r.accelerationX.stdDev - Math.sqrt(expectedAxVar)) < 1e-10, "wrong acceleration std");
            require(Math.abs(r.accelerationX.min - 0.1) < 1e-12 &&
                            Math.abs(r.accelerationX.max - 0.2) < 1e-12, "wrong acceleration extrema");
            double expectedTemp = (301.0 * 30.0 + 300.0 * 32.0) / 601.0;
            require(Math.abs(r.temperature.mean - expectedTemp) < 1e-12 &&
                            Math.abs(r.temperature.stdDev - Math.sqrt(
                                    (301.0 * Math.pow(30.0 - expectedTemp, 2) +
                                            300.0 * Math.pow(32.0 - expectedTemp, 2)) / 601.0)) < 1e-10,
                    "wrong temperature stats");
            double expectedMagnitude = (301.0 * Math.sqrt(0.1 * 0.1 + 9.8 * 9.8) +
                    300.0 * Math.sqrt(0.2 * 0.2 + 9.8 * 9.8)) / 601.0;
            require(Math.abs(r.accelerationMagnitude.mean - expectedMagnitude) < 1e-12,
                    "wrong acceleration vector magnitude");
            require(Math.abs(r.gyroX.mean + 7.1) < 1e-12, "gyro bias was silently removed");
            require(Math.abs(r.gyroMagnitude.mean - Math.sqrt(7.1 * 7.1 + 0.2 * 0.2 + 0.1 * 0.1)) < 0.001,
                    "wrong gyro vector magnitude");
            require(r.firstBoardAt == 0 && r.lastBoardAt == 60000 &&
                            r.firstReceivedAt == 0 && r.lastReceivedAt == 60000 &&
                            r.boardIntervalMin == 100 && r.boardIntervalMax == 100 &&
                            r.receivedIntervalMin == 100 && r.receivedIntervalMax == 100,
                    "capture clocks or interval extrema changed");
        });

        test("duplicate, backward, and large-gap timestamps fail the capture", () -> {
            ImuStaticBaseline duplicate = new ImuStaticBaseline();
            duplicate.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(duplicate, 100, 42);
            require(duplicate.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)), "first sample");
            fresh(duplicate, 100, 43);
            require(!duplicate.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            duplicate.state() == ImuStaticBaseline.State.FAILED, "duplicate accepted");

            ImuStaticBaseline backward = new ImuStaticBaseline();
            backward.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(backward, 100, 42);
            require(backward.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)), "first sample");
            fresh(backward, 200, 43);
            require(!backward.onSample(sample(99, 200, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            backward.state() == ImuStaticBaseline.State.FAILED, "backward timestamp accepted");

            ImuStaticBaseline gap = new ImuStaticBaseline();
            gap.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(gap, 100, 42);
            require(gap.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)), "first sample");
            fresh(gap, 851, 43);
            require(!gap.onSample(sample(851, 851, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            gap.state() == ImuStaticBaseline.State.FAILED, "large gap accepted");
        });

        test("stale or future status and health are rejected", () -> {
            ImuStaticBaseline stale = new ImuStaticBaseline();
            stale.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(stale, 0, 42);
            require(stale.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)), "first sample");
            stale.updateStatus(status(0, 42), 0);
            stale.updateHealth(health(0, 42, 0, 0, 0, 0));
            require(!stale.onSample(sample(100, 2601, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            stale.state() == ImuStaticBaseline.State.FAILED, "stale frame accepted");

            ImuStaticBaseline future = new ImuStaticBaseline();
            future.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            future.updateStatus(status(10, 42), 10);
            future.updateHealth(health(10, 42, 0, 0, 0, 0));
            require(!future.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            future.state() == ImuStaticBaseline.State.FAILED, "future status accepted");
        });

        test("new health gaps, saturation, and motion fail while historical counts are tolerated", () -> {
            ImuStaticBaseline historical = new ImuStaticBaseline();
            historical.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            historical.updateStatus(status(0, 42), 0);
            historical.updateHealth(health(0, 42, 3, 2, 4, 5));
            require(historical.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)),
                    "historical health counts should be baseline");

            ImuStaticBaseline badHealth = new ImuStaticBaseline();
            badHealth.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(badHealth, 0, 42);
            require(badHealth.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)), "first sample");
            badHealth.updateHealth(health(100, 43, 4, 2, 4, 5));
            require(!badHealth.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            badHealth.state() == ImuStaticBaseline.State.FAILED, "new health error accepted");

            ImuStaticBaseline counterRollback = new ImuStaticBaseline();
            counterRollback.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(counterRollback, 0, 42);
            require(counterRollback.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)), "first sample");
            counterRollback.updateStatus(status(100, 41), 100);
            counterRollback.updateHealth(health(100, 41, 0, 0, 0, 0));
            require(!counterRollback.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            counterRollback.state() == ImuStaticBaseline.State.FAILED,
                    "counter rollback accepted");

            ImuStaticBaseline motion = new ImuStaticBaseline();
            motion.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            fresh(motion, 0, 42);
            require(!motion.onSample(sample(0, 0, 20, 0, 0, 0, 0, 0, 30)) &&
                            motion.state() == ImuStaticBaseline.State.FAILED, "motion accepted");
        });

        test("orientation changes fail on axis variance even with constant vector magnitude", () -> {
            ImuStaticBaseline baseline = new ImuStaticBaseline();
            baseline.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i <= 600; i++) {
                long at = i * 100L;
                fresh(baseline, at, 42L + i);
                double az = i < 300 ? 9.8 : -9.8;
                require(baseline.onSample(sample(at, at, 0, 0, az, -7.1, 0.2, 0.1, 30)),
                        "orientation sample rejected before variance check");
            }
            require(baseline.state() == ImuStaticBaseline.State.FAILED &&
                            baseline.reason().contains("方差"), "orientation change passed static gate");
        });

        test("board-clock duration cannot replace 60 seconds of receive-clock coverage", () -> {
            ImuStaticBaseline baseline = new ImuStaticBaseline();
            baseline.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i <= 600; i++) {
                long boardAt = i * 100L;
                long receivedAt = i * 10L;
                fresh(baseline, receivedAt, 42L + i);
                require(baseline.onSample(sample(boardAt, receivedAt, 0, 0, 9.8, -7, 0, 0, 30)),
                        "burst sample rejected too early");
            }
            require(baseline.state() == ImuStaticBaseline.State.COLLECTING,
                    "board duration incorrectly completed capture");
            baseline.tick(90_000L);
            require(baseline.state() == ImuStaticBaseline.State.FAILED,
                    "receive-clock timeout did not fail capture");
        });

        test("60 seconds without 400 samples stays incomplete and final health must cover the window", () -> {
            ImuStaticBaseline sparse = new ImuStaticBaseline();
            sparse.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i <= 200; i++) {
                long at = i * 300L;
                fresh(sparse, at, 42L + i);
                require(sparse.onSample(sample(at, at, 0, 0, 9.8, -7, 0, 0, 30)), "sparse sample rejected");
            }
            require(sparse.state() == ImuStaticBaseline.State.COLLECTING &&
                            sparse.acceptedSamples() == 201,
                    "insufficient sample count completed capture");

            ImuStaticBaseline finalHealth = new ImuStaticBaseline();
            finalHealth.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i <= 600; i++) {
                long at = i * 100L;
                if (i % 10 == 0 && i < 600) fresh(finalHealth, at, 42L + i);
                require(finalHealth.onSample(sample(at, at, 0, 0, 9.8, -7, 0, 0, 30)),
                        "low rate health should support healthy collection");
            }
            require(finalHealth.state() == ImuStaticBaseline.State.COLLECTING,
                    "capture completed before final health evidence");
            finalHealth.updateStatus(status(60010, 643), 60010);
            finalHealth.updateHealth(health(60011, 643, 0, 0, 0, 0));
            require(finalHealth.state() == ImuStaticBaseline.State.COMPLETE,
                    "final health did not complete ready capture");
        });

        test("replayed cached samples fail when health samples or status board time stops advancing", () -> {
            ImuStaticBaseline frozenHealth = new ImuStaticBaseline();
            frozenHealth.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i < 40; i++) {
                long at = i * 100L;
                frozenHealth.updateStatus(status(at, 42L + i), at);
                frozenHealth.updateHealth(health(at, 42, 0, 0, 0, 0));
                if (!frozenHealth.onSample(sample(at, at, 0, 0, 9.8, -7, 0, 0, 30))) break;
            }
            require(frozenHealth.state() == ImuStaticBaseline.State.FAILED &&
                            frozenHealth.reason().contains("健康样本计数"),
                    "frozen health sample count was accepted");

            ImuStaticBaseline frozenStatus = new ImuStaticBaseline();
            frozenStatus.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            for (int i = 0; i < 40; i++) {
                long at = i * 100L;
                frozenStatus.updateStatus(status(0, 42L + i), at);
                frozenStatus.updateHealth(health(at, 42L + i, 0, 0, 0, 0));
                if (!frozenStatus.onSample(sample(at, at, 0, 0, 9.8, -7, 0, 0, 30))) break;
            }
            require(frozenStatus.state() == ImuStaticBaseline.State.FAILED &&
                            frozenStatus.reason().contains("状态样本计数"),
                    "frozen status board time was accepted");
        });

        test("transient health counter errors remain latched without an intervening sample", () -> {
            for (int issue = 0; issue < 4; issue++) {
                ImuStaticBaseline baseline = awaitingFinalEvidence();
                baseline.updateHealth(health(60010, 643,
                        issue == 0 ? 1 : 0, issue == 1 ? 1 : 0,
                        issue == 2 ? 1 : 0, issue == 3 ? 1 : 0));
                // Both summaries arrive before the next sample. The original
                // counters returning to zero must not erase evidence of an error.
                baseline.updateHealth(health(60011, 644, 0, 0, 0, 0));
                baseline.updateStatus(status(60012, 644), 60012);
                requireLatchedInvalid(baseline, "transient health error " + issue);
            }
        });

        test("status and health counter rollbacks stay latched after restored summaries", () -> {
            ImuStaticBaseline statusSamples = awaitingFinalEvidence();
            statusSamples.updateStatus(status(60010, 631), 60010);
            statusSamples.updateStatus(status(60012, 644), 60012);
            statusSamples.updateHealth(health(60013, 644, 0, 0, 0, 0));
            requireLatchedInvalid(statusSamples, "status sample counter rollback");

            ImuStaticBaseline statusBoardTime = awaitingFinalEvidence();
            statusBoardTime.updateStatus(status(58900, 643), 60010);
            statusBoardTime.updateStatus(status(60012, 644), 60012);
            statusBoardTime.updateHealth(health(60013, 644, 0, 0, 0, 0));
            requireLatchedInvalid(statusBoardTime, "status board timestamp rollback");

            ImuStaticBaseline healthSamples = awaitingFinalEvidence();
            // Still above the first baseline count (42), but lower than the
            // immediately preceding health count (632).
            healthSamples.updateHealth(health(60010, 631, 0, 0, 0, 0));
            healthSamples.updateHealth(health(60011, 644, 0, 0, 0, 0));
            healthSamples.updateStatus(status(60012, 644), 60012);
            requireLatchedInvalid(healthSamples, "health sample counter rollback");
        });

        test("initial empty health can recover before a full 60 second capture", () -> {
            ImuStaticBaseline baseline = new ImuStaticBaseline();
            baseline.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
            baseline.updateStatus(status(0, 0), 0);
            baseline.updateHealth(emptyHealth(0));
            require(!baseline.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            baseline.acceptedSamples() == 0 && baseline.rejectedSamples() == 0 &&
                            baseline.state() == ImuStaticBaseline.State.COLLECTING,
                    "initial empty health must wait without accepting or rejecting samples");
            require(baseline.tick(50) == ImuStaticBaseline.State.COLLECTING,
                    "initial empty health prematurely ended capture");
            for (int i = 0; i <= 600; i++) {
                long at = 100L + i * 100L;
                fresh(baseline, at, 42L + i);
                require(baseline.onSample(sample(at, at, 0, 0, 9.8, -7, 0, 0, 30)),
                        "valid health did not recover startup wait");
            }
            require(baseline.state() == ImuStaticBaseline.State.COMPLETE &&
                            baseline.acceptedSamples() == 601 &&
                            baseline.result().firstReceivedAt == 100L &&
                            baseline.result().lastReceivedAt == 60100L,
                    "startup wait was counted as capture time or prevented completion");
        });

        test("empty health after a baseline stays latched even before the first accepted sample", () -> {
            for (int withAcceptedSample = 0; withAcceptedSample < 2; withAcceptedSample++) {
                ImuStaticBaseline baseline = new ImuStaticBaseline();
                baseline.start(ImuStaticBaseline.Pose.SCREEN_UP, 0);
                fresh(baseline, 0, 42);
                if (withAcceptedSample == 1) {
                    require(baseline.onSample(sample(0, 0, 0, 0, 9.8, -7, 0, 0, 30)),
                            "baseline sample was rejected");
                }
                baseline.updateHealth(emptyHealth(50));
                // A newer valid summary must not turn a post-baseline failure
                // back into the initial, recoverable startup state.
                fresh(baseline, 100, 43);
                require(!baseline.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)) &&
                                baseline.state() == ImuStaticBaseline.State.FAILED &&
                                !baseline.result().completeForReview(),
                        "post-baseline empty health recovered for accepted=" + withAcceptedSample);
            }
        });

        test("timeout, cancellation, and restart reset all capture state", () -> {
            ImuStaticBaseline timeout = new ImuStaticBaseline();
            timeout.start(ImuStaticBaseline.Pose.SCREEN_UP, 100);
            require(!timeout.onSample(sample(100, 100, 0, 0, 9.8, -7, 0, 0, 30)) &&
                            timeout.state() == ImuStaticBaseline.State.COLLECTING,
                    "sample before status query response did not wait");
            require(timeout.tick(90_100) == ImuStaticBaseline.State.FAILED, "timeout not enforced");
            timeout.start(ImuStaticBaseline.Pose.SCREEN_RIGHT, 200);
            require(timeout.state() == ImuStaticBaseline.State.COLLECTING &&
                            timeout.acceptedSamples() == 0 && timeout.pose() == ImuStaticBaseline.Pose.SCREEN_RIGHT,
                    "restart did not clear capture");
            timeout.cancel();
            require(timeout.state() == ImuStaticBaseline.State.CANCELLED, "cancel not recorded");
        });

        System.out.println("PASS: all IMU static baseline tests (" + passed + ")");
    }
}
