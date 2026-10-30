package com.example.motonav;

import java.nio.charset.StandardCharsets;

/**
 * Regression checks for the signed OTA BEGIN command.
 *
 * The ESP32 firmware accepts at most 512 bytes on its text RX queue.  Android
 * exposes ATT payload as MTU - 3, so the complete RSA signature metadata only
 * fits after a 517 byte MTU is negotiated.  This test documents that boundary
 * and prevents a future change from silently exceeding the firmware buffer.
 */
public final class OtaBeginSizingTest {
    private static int checks;

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        checks++;
    }

    private static int maxWrite(int mtu) {
        return Math.min(mtu - 3, 512);
    }

    private static String begin() {
        String signature = "A".repeat(344); // RSA-2048 signature in Base64.
        return "#OTA,BEGIN,175C,v7.12.9-nav.9-ride-info-imu-test,1363376," +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef," +
            "RSA-SHA256-PKCS1v1.5,4b5022f6c387bbd0," + signature;
    }

    public static void main(String[] args) {
        int bytes = begin().getBytes(StandardCharsets.UTF_8).length;
        check(bytes == 504, "signed BEGIN size is stable for the current metadata shape");
        check(bytes > 500, "regression covers the old Android write cap");
        check(bytes <= 512, "BEGIN stays within firmware RXQ_MAX");
        check(bytes > maxWrite(23), "default ATT MTU cannot carry signed BEGIN");
        check(bytes > maxWrite(185), "185 ATT MTU cannot carry signed BEGIN");
        check(bytes > maxWrite(247), "247 ATT MTU cannot carry signed BEGIN");
        check(bytes <= maxWrite(517), "517 ATT MTU carries signed BEGIN");
        check(maxWrite(517) == 512, "Android cap matches firmware RXQ_MAX");
        System.out.println("OTA BEGIN sizing: " + checks + " checks passed (" + bytes + " bytes)");
    }
}
