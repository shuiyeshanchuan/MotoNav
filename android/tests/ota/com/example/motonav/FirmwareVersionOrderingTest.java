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
        check(policy.isNotOlder(
                        "v7.12.9-nav.12-mount-reference-imu-test",
                        "v7.12.9-nav.11-ride-info-ui-fix"),
                "explicit diagnostic updates can advance nav.11 to nav.12");
        check(!policy.isNotOlder(
                        "v7.12.9-nav.11-ride-info-ui-fix",
                        "v7.12.9-nav.12-mount-reference-imu-test"),
                "nav.12 cannot be replaced by older nav.11");

        String stable = "motonav-ota-stable.json";
        String diagnostic = "motonav-ota-diagnostic.json";
        // Both choices retain formal stable releases. Neither choice promotes a
        // prerelease's stable index into an ordinary stable update.
        for (boolean includeDiagnostic : new boolean[] {false, true}) {
            check(policy.isIndexAllowed(stable, 1, "stable", includeDiagnostic, false),
                    "formal stable candidate remains available with opt-in=" + includeDiagnostic);
            check(!policy.isIndexAllowed(stable, 1, "stable", includeDiagnostic, true),
                    "prerelease stable index is excluded with opt-in=" + includeDiagnostic);
            check(!policy.isIndexAllowed(stable, 1, "diagnostic", includeDiagnostic, false),
                    "diagnostic channel cannot masquerade as the stable index");
            check(!policy.isIndexAllowed(diagnostic, 1, "stable", includeDiagnostic, false),
                    "stable channel cannot masquerade as the diagnostic index");
            check(!policy.isIndexAllowed("other-ota.json", 1, "stable", includeDiagnostic, false),
                    "unknown index names are excluded");
            for (boolean prerelease : new boolean[] {false, true}) {
                check(policy.isIndexAllowed(diagnostic, 1, "diagnostic", includeDiagnostic, prerelease)
                                == includeDiagnostic,
                        "diagnostic index requires explicit opt-in; prerelease=" + prerelease);
            }
            for (int schema : new int[] {0, 2}) {
                check(!policy.isIndexAllowed(stable, schema, "stable", includeDiagnostic, false),
                        "unsupported stable index schema is rejected");
                check(!policy.isIndexAllowed(diagnostic, schema, "diagnostic", includeDiagnostic, false),
                        "unsupported diagnostic index schema is rejected");
            }
        }
        check(!policy.isIndexAllowed(diagnostic, 1, "Diagnostic", true, false),
                "diagnostic channel must match the supported spelling exactly");
        check(policy.isDiagnosticAssetName(
                        "v7.12.9-nav.12-mount-reference-imu-test",
                        "nav12_manifest.json", "nav12_app_0x10000.bin"),
                "nav.12 remains classified diagnostic and blocked in stable indices");
        System.out.println("Firmware version ordering: " + checks + " checks passed");
    }
}
