package com.example.motonav;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Bounded complete INFO response; never treats a truncated version prefix as identity. */
public final class DeviceIdentityProtocol {
    private final byte[] body = new byte[160];
    private int frame = -1, length, offset;
    private long lastAt;

    public void reset() { frame = -1; length = offset = 0; lastAt = 0; }
    public static boolean isFrame(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && (bytes[0] & 255) == 0xd7 && bytes[1] == 0x49;
    }
    public String accept(byte[] bytes, long now) {
        if (!isFrame(bytes) || bytes.length < 9 || bytes[2] != 1 || now < 0) { reset(); return null; }
        int id = bytes[3] & 255, at = u16(bytes, 4), total = u16(bytes, 6), count = bytes.length - 8;
        if (total < 5 || total > body.length || at + count > total || count <= 0) { reset(); return null; }
        if (at == 0) { reset(); frame = id; length = total; lastAt = now; }
        if (frame != id || total != length || at != offset || now < lastAt || now - lastAt > 3000) {
            reset(); return null;
        }
        System.arraycopy(bytes, 8, body, offset, count); offset += count; lastAt = now;
        if (offset != length) return null;
        CRC32 crc = new CRC32(); crc.update(body, 0, length - 4);
        long expected = 0;
        for (int i = 0; i < 4; i++) expected |= (long)(body[length - 4 + i] & 255) << (8 * i);
        byte[] text = Arrays.copyOf(body, length - 4); reset();
        if (crc.getValue() != expected) return null;
        for (byte value : text) if (value < 32 || value > 126) return null;
        String info = new String(text, StandardCharsets.US_ASCII);
        return completeInfo(info) ? info : null;
    }
    public static boolean validVersion(String value) {
        return value != null && value.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-._][A-Za-z0-9._-]+)?");
    }
    public static boolean completeInfo(String info) {
        if (info == null || info.length() > 156) return false;
        String[] p = info.split(",", -1);
        if (p.length != 9 || !p[0].equals("INFO") || !p[1].matches("[A-Za-z0-9]+") || !validVersion(p[2])) return false;
        for (int i = 3; i < p.length; i++) if (!p[i].matches("[0-9]{1,5}")) return false;
        return true;
    }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
}
