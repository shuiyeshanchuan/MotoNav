package com.example.motonav;

/** Pure JVM replay checks for the record-only accident safety model. */
public final class AccidentSafetyModelTest {
    private static int passed;

    private static final AccidentSafetyModel.Config CONFIG = new AccidentSafetyModel.Config(
            12.0, 100.0, 20.0, 50.0, 100.0, 40.0,
            3.0, 45.0, 5_000L, 2_000L, 500L, 1_000L, 1_000L);

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static AccidentSafetyModel.SensorSample rest(long at, double speed) {
        return AccidentSafetyModel.SensorSample.healthyRest(at, speed, 8.0);
    }

    private static AccidentSafetyModel.SensorSample riding(long at, double speed) {
        return AccidentSafetyModel.SensorSample.healthy(
                at, AccidentSafetyModel.STANDARD_GRAVITY_MPS2, 0.0, speed, 8.0, 0.0);
    }

    private static AccidentSafetyModel.SensorSample impact(long at) {
        return AccidentSafetyModel.SensorSample.healthy(at, 24.0, 180.0, 48.0, 8.0, 65.0);
    }

    private static AccidentSafetyModel.Detector detector() {
        return new AccidentSafetyModel.Detector(CONFIG);
    }

    public static void main(String[] args) {
        test("disabled detector never creates an event", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.onSample(riding(0, 50.0));
            d.onSample(impact(100));
            require(d.state() == AccidentSafetyModel.State.DISABLED, "disabled state changed");
            require(d.lastRecordedEvent() == null, "disabled detector recorded an event");
        });

        test("low speed impact without a riding session is ignored", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(rest(0, 0.0));
            d.onSample(AccidentSafetyModel.SensorSample.healthy(100, 24.0, 180.0, 0.0, 8.0, 65.0));
            require(d.state() == AccidentSafetyModel.State.ARMED, "parked impact became candidate");
        });

        test("impact sample speed alone is not prior riding evidence", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(impact(0));
            require(d.state() == AccidentSafetyModel.State.ARMED, "impact speed was treated as prior ride");
        });

        test("a single acceleration spike without rotation or tilt is ignored", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(riding(0, 50.0));
            d.onSample(AccidentSafetyModel.SensorSample.healthy(100, 24.0, 0.0, 48.0, 8.0, 0.0));
            d.onSample(rest(600, 0.0));
            d.onSample(rest(1_100, 0.0));
            require(d.state() == AccidentSafetyModel.State.ARMED, "single spike triggered");
            require(d.lastRecordedEvent() == null, "single spike recorded");
        });

        test("impact, rotation, and post-impact stillness enter countdown", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(riding(0, 50.0));
            d.onSample(impact(100));
            require(d.state() == AccidentSafetyModel.State.CANDIDATE, "multi-signal impact not a candidate");
            d.onSample(rest(500, 0.0));
            require(d.state() == AccidentSafetyModel.State.CANDIDATE, "candidate ended too early");
            d.onSample(rest(1_000, 0.0));
            require(d.state() == AccidentSafetyModel.State.COUNTDOWN, "stillness did not enter countdown");
            require(d.countdownRemainingMs(1_000) == CONFIG.countdownMs, "wrong countdown duration");
        });

        test("countdown expiry records locally and has no notification side effect", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(riding(0, 50.0));
            d.onSample(impact(100));
            d.onSample(rest(500, 0.0));
            d.onSample(rest(1_000, 0.0));
            d.tick(1_999);
            require(d.state() == AccidentSafetyModel.State.COUNTDOWN, "countdown expired early");
            d.tick(2_000);
            require(d.state() == AccidentSafetyModel.State.RECORDED, "event was not recorded");
            AccidentSafetyModel.AccidentEvent event = d.lastRecordedEvent();
            require(event != null, "recorded event missing");
            require(event.eventId == 1L, "unexpected event id");
            require(event.peakSpeedKmh >= 48.0, "peak speed was not retained");
            require("IMPACT_ROTATION_STILLNESS".equals(event.trigger), "unexpected trigger reason");
            // The model has no contacts, SMS, dialer, or notification callback by design.
        });

        test("user cancellation prevents a recorded event", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(riding(0, 50.0));
            d.onSample(impact(100));
            d.onSample(rest(500, 0.0));
            d.onSample(rest(1_000, 0.0));
            d.cancel();
            d.tick(2_000);
            require(d.state() == AccidentSafetyModel.State.CANCELLED, "cancel did not stick");
            require(d.lastRecordedEvent() == null, "cancelled event was recorded");
        });

        test("unhealthy sensor or link makes the model unavailable", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            AccidentSafetyModel.SensorSample bad = new AccidentSafetyModel.SensorSample(
                    0, 0, 0, AccidentSafetyModel.STANDARD_GRAVITY_MPS2,
                    0, 0, 0, 40.0, 8.0, 0.0,
                    true, false, true, true, false);
            d.onSample(bad);
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "bad IMU did not disable detector");
            d.onSample(impact(100));
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "unavailable detector rearmed itself");
        });

        test("duplicate, backward, and large-gap samples become unavailable", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(riding(100, 50.0));
            d.onSample(impact(100));
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "duplicate sample was accepted");
            d.arm();
            d.onSample(riding(0, 50.0));
            d.onSample(impact(2_000));
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "large sample gap was accepted");
            d.arm();
            d.onSample(riding(100, 50.0));
            d.onSample(impact(50));
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "backward sample was accepted");
            require(d.lastRecordedEvent() == null, "invalid sample sequence recorded");
        });

        test("missing location or BLE link is unavailable and cannot trigger", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            AccidentSafetyModel.SensorSample noLocation = new AccidentSafetyModel.SensorSample(
                    0, 0, 0, AccidentSafetyModel.STANDARD_GRAVITY_MPS2,
                    0, 0, 0, 40.0, 8.0, 0.0,
                    false, true, true, true, false);
            d.onSample(noLocation);
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "missing location was accepted");
            d.arm();
            AccidentSafetyModel.SensorSample noBle = new AccidentSafetyModel.SensorSample(
                    0, 0, 0, AccidentSafetyModel.STANDARD_GRAVITY_MPS2,
                    0, 0, 0, 40.0, 8.0, 0.0,
                    true, true, false, true, false);
            d.onSample(noBle);
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "BLE disconnect was accepted");
        });

        test("candidate timeout returns to armed without recording", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            d.onSample(riding(0, 50.0));
            d.onSample(impact(100));
            d.tick(2_101);
            require(d.state() == AccidentSafetyModel.State.ARMED, "candidate did not expire");
            require(d.lastRecordedEvent() == null, "expired candidate recorded");
        });

        test("sensor saturation is rejected before any incident decision", () -> {
            AccidentSafetyModel.Detector d = detector();
            d.arm();
            AccidentSafetyModel.SensorSample saturated = new AccidentSafetyModel.SensorSample(
                    0, 30, 0, 0, 0, 0, 0, 50, 8, 70,
                    true, true, true, true, true);
            d.onSample(saturated);
            require(d.state() == AccidentSafetyModel.State.UNAVAILABLE, "saturated sample was accepted");
            require(d.lastRecordedEvent() == null, "saturated sample recorded");
        });

        System.out.println("PASS: " + passed + " accident safety model scenarios");
    }
}
