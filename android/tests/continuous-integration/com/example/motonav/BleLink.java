package com.example.motonav;

import java.util.ArrayList;
import java.util.List;

/** The real recorder talks to this controllable transport, not an Android radio. */
public final class BleLink {
    public static final BleLink INSTANCE = new BleLink();
    public static volatile boolean connected = true, verified = true, supported = true, ota;
    public static volatile boolean firmwareComplete;
    public static volatile String firmware = "v7.12.9-na";
    private static final List<byte[]> controls = new ArrayList<>();
    public boolean isConnected() { return connected; }
    public boolean getVerified() { return verified; }
    public boolean getSupportsContinuousImu() { return supported; }
    public boolean getOtaInProgress() { return ota; }
    public String getScreenFirmware() { return firmware; }
    public boolean getScreenFirmwareComplete() { return firmwareComplete; }
    public ImuMountCalibration.Frame getScreenImuMountReference() { return null; }
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
