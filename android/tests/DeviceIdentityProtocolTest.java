package com.example.motonav;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

public class DeviceIdentityProtocolTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        ArrayList<byte[]> packets = new ArrayList<>();
        for (int at = 0; at < bytes.length;) {
            int size = bytes[at++] & 255;
            packets.add(Arrays.copyOfRange(bytes, at, at + size)); at += size;
        }
        check(packets.size() > 1);
        String expected = "INFO,175C,v7.12.9-nav.15-imu-sampling-log-test,1,1,1,32,466,466";
        DeviceIdentityProtocol parser = new DeviceIdentityProtocol();
        String result = null;
        long now = 100;
        for (byte[] packet : packets) result = parser.accept(packet, now += 50);
        check(expected.equals(result));
        check(!DeviceIdentityProtocol.completeInfo("INFO,175C,v7.12.9-na"));
        check(DeviceIdentityProtocol.completeInfo(expected));
        check(!DeviceIdentityProtocol.completeInfo(expected + ",0"));
        check(!DeviceIdentityProtocol.completeInfo(expected.replace(",466,466", ",466,")));
        check(parser.accept(packets.get(1), now += 50) == null); // missing first fragment
        parser.accept(packets.get(0), now += 50);
        check(parser.accept(packets.get(1), now += 3001) == null);
        for (byte[] packet : packets) {
            byte[] bad = packet.clone(); bad[8] ^= 1;
            check(parser.accept(bad, now += 50) == null);
        }
        for (byte[] packet : packets) result = parser.accept(packet, now += 50);
        check(expected.equals(result)); // parser recovers after corruption
        byte[] huge = packets.get(0).clone(); huge[6] = (byte)161;
        check(parser.accept(huge, now += 50) == null);
        parser.accept(packets.get(0), now += 50); parser.reset();
        check(parser.accept(packets.get(1), now += 50) == null); // new connection
        parser.accept(packets.get(0), now += 50);
        byte[] wrongId = packets.get(1).clone(); wrongId[3]++;
        check(parser.accept(wrongId, now += 50) == null);
        System.out.println("DeviceIdentityProtocol: native cross-language fallback-MTU, CRC, timeout, loss, mixed frames, truncation passed");
    }
}
