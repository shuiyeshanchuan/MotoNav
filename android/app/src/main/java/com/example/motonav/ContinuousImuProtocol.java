package com.example.motonav;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/** Version-one continuous IMU transport. No accident decisions or Android dependencies. */
public final class ContinuousImuProtocol {
    public static final int START = 1, STOP = 2, ACK = 3, QUERY = 4;
    public static final int ACTIVE = 1, STOPPED = 2, SENSOR_BAD = 4, ALLOCATION_FAILED = 8;
    public static final int MAX_SAMPLES = 8, MAX_BODY_BYTES = 308;
    public static final long UINT32_MAX = 0xffff_ffffL;

    private ContinuousImuProtocol() {}

    public static byte[] encodeControl(int opcode, long session, long boot, long argument) {
        if (opcode < START || opcode > QUERY) throw new IllegalArgumentException("unknown IMU opcode");
        uint32(session); uint32(boot); uint32(argument);
        ByteBuffer b = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0xd7).put((byte) 0x43).put((byte) 1).put((byte) opcode);
        b.putInt((int) session).putInt((int) boot).putInt((int) argument);
        b.putShort((short) crc16(b.array(), 16));
        return b.array();
    }

    private static void uint32(long value) {
        if (value < 0 || value > UINT32_MAX) throw new IllegalArgumentException("IMU uint32 out of range");
    }

    public static int crc16(byte[] data, int length) {
        int crc = 0xffff;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 255) << 8;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xffff;
        }
        return crc;
    }

    public static final class Sample {
        public final long timestamp;
        public final float ax, ay, az, gx, gy, gz, temperature;
        private Sample(ByteBuffer b) {
            timestamp = Integer.toUnsignedLong(b.getInt());
            ax = b.getFloat(); ay = b.getFloat(); az = b.getFloat();
            gx = b.getFloat(); gy = b.getFloat(); gz = b.getFloat(); temperature = b.getFloat();
            if (!Float.isFinite(ax) || !Float.isFinite(ay) || !Float.isFinite(az) ||
                    !Float.isFinite(gx) || !Float.isFinite(gy) || !Float.isFinite(gz) ||
                    !Float.isFinite(temperature)) throw new IllegalArgumentException("nonfinite IMU sample");
        }
    }

    public static final class Batch {
        public final long session, boot, firstSeq, nextSeq, oldestSeq;
        public final long overflow, missedSlots, readErrors, boardNow, startMillis, stopMillis;
        public final int flags;
        public final List<Sample> samples;
        private Batch(ByteBuffer b) {
            session = u32(b); boot = u32(b); firstSeq = u32(b); nextSeq = u32(b); oldestSeq = u32(b);
            overflow = u32(b); missedSlots = u32(b); readErrors = u32(b);
            boardNow = u32(b); startMillis = u32(b); stopMillis = u32(b);
            flags = Short.toUnsignedInt(b.getShort());
            int count = Short.toUnsignedInt(b.getShort());
            if (count > MAX_SAMPLES || b.limit() != 48 + 32 * count + 4 ||
                    firstSeq + count > nextSeq || oldestSeq > nextSeq || (flags & ~15) != 0 ||
                    (flags & (ACTIVE | STOPPED)) == (ACTIVE | STOPPED))
                throw new IllegalArgumentException("inconsistent IMU batch");
            List<Sample> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) values.add(new Sample(b));
            samples = Collections.unmodifiableList(values);
        }
        public boolean isActive() { return (flags & ACTIVE) != 0; }
        public boolean isStopped() { return (flags & STOPPED) != 0; }
    }

    private static long u32(ByteBuffer b) { return Integer.toUnsignedLong(b.getInt()); }

    public static Batch parseBody(byte[] body) {
        if (body == null || body.length < 52 || body.length > MAX_BODY_BYTES)
            throw new IllegalArgumentException("invalid IMU body length");
        CRC32 crc = new CRC32(); crc.update(body, 0, body.length - 4);
        ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        if (crc.getValue() != Integer.toUnsignedLong(b.getInt(body.length - 4)))
            throw new IllegalArgumentException("IMU body CRC32 mismatch");
        return new Batch(b);
    }

    /** At most 308 payload bytes, one frame, including out-of-order/duplicate fragments. */
    public static final class Assembler {
        private final long timeoutMs;
        private int frameId = -1, parts, total, receivedBytes, receivedParts;
        private long startedAt;
        private byte[][] fragments;
        public Assembler(long timeoutMs) {
            if (timeoutMs <= 0) throw new IllegalArgumentException("invalid fragment timeout");
            this.timeoutMs = timeoutMs;
        }
        public Assembler() { this(2000L); }
        public void reset() { frameId = -1; fragments = null; receivedBytes = receivedParts = 0; }

        public Batch accept(byte[] fragment, long receivedAtMonotonicMs) {
            if (fragment == null || fragment.length <= 8 || receivedAtMonotonicMs < 0 ||
                    (fragment[0] & 255) != 0xd7 || (fragment[1] & 255) != 0x52 || fragment[2] != 1)
                throw new IllegalArgumentException("invalid IMU fragment header");
            int id = fragment[3] & 255, index = fragment[4] & 255, count = fragment[5] & 255;
            int length = (fragment[6] & 255) | (fragment[7] & 255) << 8;
            if (length < 52 || length > MAX_BODY_BYTES || count < 1 || count > length || index >= count ||
                    fragment.length - 8 > length) throw new IllegalArgumentException("invalid IMU fragment size");
            if (frameId != id || receivedAtMonotonicMs < startedAt || receivedAtMonotonicMs - startedAt > timeoutMs) {
                reset(); frameId = id; parts = count; total = length; startedAt = receivedAtMonotonicMs;
                fragments = new byte[parts][];
            } else if (parts != count || total != length) {
                reset(); throw new IllegalArgumentException("IMU fragment frame changed");
            }
            byte[] payload = Arrays.copyOfRange(fragment, 8, fragment.length);
            if (fragments[index] != null) {
                if (!Arrays.equals(fragments[index], payload)) {
                    reset(); throw new IllegalArgumentException("conflicting IMU fragment duplicate");
                }
                return null;
            }
            if (receivedBytes + payload.length > total) {
                reset(); throw new IllegalArgumentException("IMU fragments exceed body size");
            }
            fragments[index] = payload; receivedBytes += payload.length; receivedParts++;
            if (receivedParts != parts) return null;
            if (receivedBytes != total) {
                reset(); throw new IllegalArgumentException("incomplete IMU frame length");
            }
            byte[] body = new byte[total]; int offset = 0;
            for (byte[] part : fragments) { System.arraycopy(part, 0, body, offset, part.length); offset += part.length; }
            reset();
            return parseBody(body);
        }
    }
}
