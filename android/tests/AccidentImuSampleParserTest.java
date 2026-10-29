package com.example.motonav;

/** Pure JVM checks for the opt-in BLE IMU diagnostic wire format. */
public final class AccidentImuSampleParserTest {
    private static int passed;

    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        test("sample frame parses with phone receive clock", () -> {
            AccidentImuSampleParser.Frame frame = AccidentImuSampleParser.parse(
                    "IMU,12345,0.100,-0.200,9.700,1.25,-2.50,3.75,31.2", 987654L);
            require(frame instanceof AccidentImuSampleParser.Sample, "sample frame was rejected");
            AccidentImuSampleParser.Sample sample = (AccidentImuSampleParser.Sample) frame;
            require(sample.boardMillis == 12345L, "board timestamp changed");
            require(sample.receivedAtMonotonicMs == 987654L, "receive timestamp changed");
            require(Math.abs(sample.accelerationMagnitudeMps2() - 9.701) < 0.02,
                    "acceleration magnitude is wrong");
            require(Math.abs(sample.gyroMagnitudeDegPerSec() - 4.677) < 0.02,
                    "gyro magnitude is wrong");
            require(sample.finite(), "finite sample marked invalid");
        });

        test("status frame exposes health and counters", () -> {
            AccidentImuSampleParser.Frame frame = AccidentImuSampleParser.parse(
                    "IMU,STATUS,6A,1,1,1,42,0,12345", 1000L);
            require(frame instanceof AccidentImuSampleParser.Status, "status frame was rejected");
            AccidentImuSampleParser.Status status = (AccidentImuSampleParser.Status) frame;
            require(status.addressKnown && status.address == 0x6A, "I2C address changed");
            require(status.present && status.configured && status.streamEnabled,
                    "status flags changed");
            require(status.samples == 42L && status.readErrors == 0L &&
                            status.lastBoardMillis == 12345L,
                    "status counters changed");
            require(!status.selfTestKnown && !status.usable(),
                    "status without self-test was accepted as safe");
        });

        test("legacy status frame remains readable", () -> {
            AccidentImuSampleParser.Status status = (AccidentImuSampleParser.Status)
                    AccidentImuSampleParser.parse("IMU,STATUS,1,1,1,42,0,12345", 1000L);
            require(!status.addressKnown && status.address == -1, "legacy address was invented");
            require(!status.selfTestKnown && !status.usable(),
                    "legacy status without self-test was accepted as safe");
        });

        test("self-test status is required when present", () -> {
            AccidentImuSampleParser.Status good = (AccidentImuSampleParser.Status)
                    AccidentImuSampleParser.parse("IMU,STATUS,6A,1,1,1,1,42,0,12345", 1000L);
            require(good.selfTestKnown && good.selfTestOk && good.usable(),
                    "successful self-test was not retained");
            AccidentImuSampleParser.Status bad = (AccidentImuSampleParser.Status)
                    AccidentImuSampleParser.parse("IMU,STATUS,6A,1,0,1,1,42,0,12345", 1000L);
            require(bad.selfTestKnown && !bad.selfTestOk && !bad.usable(),
                    "failed self-test was accepted");
        });

        test("read errors make status unusable", () -> {
            AccidentImuSampleParser.Status status = (AccidentImuSampleParser.Status)
                    AccidentImuSampleParser.parse("IMU,STATUS,6B,1,1,1,1,42,2,12345", 1000L);
            require(status.selfTestKnown && status.readErrors == 2L && !status.usable(),
                    "read errors were ignored");
        });

        test("malformed and unrelated frames are ignored", () -> {
            require(AccidentImuSampleParser.parse("IMU,1,2,3", 1000L) == null,
                    "short sample was accepted");
            require(AccidentImuSampleParser.parse("IMU,STATUS,1,1", 1000L) == null,
                    "short status was accepted");
            require(AccidentImuSampleParser.parse("IMU,STATUS,GG,1,1,1,42,0,12345", 1000L) == null,
                    "invalid I2C address was accepted");
            require(AccidentImuSampleParser.parse("IMU,STATUS,6A,1,2,1,1,42,0,12345", 1000L) == null,
                    "invalid self-test flag was accepted");
            require(AccidentImuSampleParser.parse("IMU,1,NaN,0,0,0,0,0,30", 1000L) == null,
                    "NaN sample was accepted");
            require(AccidentImuSampleParser.parse("BLE,1,2,3", 1000L) == null,
                    "unrelated frame was accepted");
            require(AccidentImuSampleParser.parse("IMU,1,0,0,0,0,0,0,30", -1L) == null,
                    "negative receive clock was accepted");
        });

        System.out.println("PASS: " + passed + " IMU parser scenarios");
    }
}
