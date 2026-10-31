package com.example.motonav;

/**
 * Regression checks against the compiled Kotlin OTA version policy.
 *
 * nav.10 has a descriptive suffix after its numeric build label. The test
 * must call FirmwareUpdateClient itself so a copied parser cannot hide a
 * regression in the production ordering logic.
 */
public final class FirmwareVersionOrderingTest {
    private static int checks;

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) {
        FirmwareUpdateClient policy = FirmwareUpdateClient.INSTANCE;
        check(policy.isNotOlder(
                        "v7.12.9-nav.10-ride-info-imu-test",
                        "v7.12.9-nav.9-ride-info-imu-test"),
                "nav.10 is newer than nav.9 with descriptive suffix");
        check(!policy.isNotOlder(
                        "v7.12.9-nav.9-ride-info-imu-test",
                        "v7.12.9-nav.10-ride-info-imu-test"),
                "nav.9 is older than nav.10 with descriptive suffix");
        check(policy.isNotOlder(
                        "v7.12.9-nav.10-ride-info-imu-test",
                        "v7.12.9-nav.10-ride-info-imu-test"),
                "equal nav.10 is accepted");
        check(policy.isNotOlder("v7.12.9-nav.10-ride-info-imu-test", "v7.12.9-nav.6"),
                "nav.10 is newer than nav.6");
        check(policy.isNotOlder("v7.12.9-power.18", "v7.12.9-power.16"),
                "existing power labels remain ordered");
        check(policy.isDiagnosticAssetName(
                        "v7.12.9-nav.11-ride-info-ui-fix",
                        "nav11-imu-diagnostic_manifest.json",
                        "nav11-imu-diagnostic_app_0x10000.bin"),
                "nav11 diagnostic image is excluded from stable OTA");
        check(!policy.isDiagnosticAssetName(
                        "v7.12.9-nav.6", "nav6_manifest.json", "nav6_app_0x10000.bin"),
                "formal nav.6 image remains eligible for stable OTA");
        System.out.println("Firmware version ordering: " + checks + " checks passed");
    }
}
