package com.example.motonav;

import java.util.ArrayList;
import java.util.List;

/** The real recorder talks to this controllable transport, not an Android radio. */
public final class BleLink {
    public static final BleLink INSTANCE = new BleLink();
    public static volatile boolean connected = true, verified = true, supported = true, ota;
    private static final List<byte[]> controls = new ArrayList<>();
    public boolean isConnected() { return connected; }
    public boolean getVerified() { return verified; }
    public boolean getSupportsContinuousImu() { return supported; }
    public boolean getOtaInProgress() { return ota; }
    public String getScreenFirmware() { return "integration-fixture"; }
    public String getScreenName() { return "fixture"; }
    public void send(String text) {}
    public void sendImuRecordControl(byte[] packet) {
        synchronized (controls) { controls.add(packet.clone()); }
    }
    public static List<byte[]> commands() {
        synchronized (controls) { return new ArrayList<>(controls); }
    }
    public static void clear() { synchronized (controls) { controls.clear(); } }
}
