package com.example.motonav;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** Exercises the actual compiled Kotlin recorder with real protocol/store and disk files. */
public final class ContinuousImuRecorderIntegrationTest {
    private static int assertions;
    private static final ContinuousImuRecorder recorder = ContinuousImuRecorder.INSTANCE;

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        recorder.init(new Context(root));
        if (args[1].equals("normal")) normal();
        else if (args[1].equals("reopen")) reopen();
        else if (args[1].equals("interruptions")) interruptions();
        else if (args[1].equals("fault-reopen")) faultReopen();
        else throw new IllegalArgumentException("test mode");
        System.out.println("PASS: real Kotlin recorder integration " + args[1] + " (" + assertions + " assertions)");
    }

    private static void normal() throws Exception {
        BleLink.clear();
        recorder.navigationStarted("巡航", "excluded cruise", false);
        Thread.sleep(450);
        check(lastCommand(1, 0) == null, "cruise must not START IMU recording");
        long id = start("normal route", false);
        emit(id, 7, 0, 8, 1);
        await(() -> hasAck(id, 7, 8), "ACK follows persisted first batch");
        check(snapshot().writtenSamples == 8, "first batch is persisted");
        check(sampleRows() == 8, "CSV has actual eight sample rows before ACK observation");
        emit(id, 7, 0, 8, 1);
        await(() -> snapshotUnchecked().duplicateSamples == 8, "retransmitted sample deduplication");
        check(snapshot().writtenSamples == 8, "duplicate did not append duplicate samples");

        BleLink.connected = false; SystemClock.advance(4000);
        await(() -> recorder.getMessage().contains("连接中断"), "disconnection status");
        BleLink.clear(); BleLink.connected = true; SystemClock.advance(4000);
        await(() -> lastCommand(1, id) != null, "same navigation resumes after BLE reconnect");
        byte[] resume = lastCommand(1, id);
        check(u32(resume, 8) == 7 && u32(resume, 12) == 8, "resume contains durable boot and next cursor");
        emit(id, 7, 8, 8, 1);
        await(() -> hasAck(id, 7, 16), "resumed data ACK");
        check(snapshot().writtenSamples == 16, "resumed samples remain in same session");
        emit(id, 7, 20, 2, 1);
        await(() -> hasAck(id, 7, 22), "gap and subsequent samples are durably acknowledged");
        check(snapshot().gapSamples == 4 && snapshot().writtenSamples == 18, "missing sequences are gaps, never invented rows");

        emit(id, 8, 0, 2, 1);
        await(() -> hasAck(id, 8, 2), "new board boot gets independent cursor");
        check(snapshot().segments == 2 && snapshot().writtenSamples == 20, "boot reset starts another segment");
        check(snapshot().tailUnknown, "board restart retains unknown previous segment tail");

        stop(id, 8, 2);
        check(!snapshot().active, "normal stop closes file");
        check(snapshot().tailUnknown, "normal stop cannot erase unknown boot-reset tail");
        check(sampleRows() == 20, "CSV count across board segments");
        final AtomicReference<File> archive = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        recorder.exportLatest((file, message) -> { archive.set(file); error.set(message); return kotlin.Unit.INSTANCE; });
        await(() -> archive.get() != null || error.get() != null, "ZIP export completion callback");
        check(error.get() == null && archive.get().length() > 0, "ZIP export succeeded");
        try (ZipFile zip = new ZipFile(archive.get())) {
            check(zip.getEntry("samples.csv") != null && zip.getEntry("metadata.properties") != null,
                    "ZIP includes raw samples and integrity metadata");
        }
    }

    private static void reopen() throws Exception {
        await(() -> recorder.getLatestDirectory() != null, "completed record remains available after app restart");
        check(new File(recorder.getLatestDirectory(), "samples.csv").isFile(), "restart export source exists");
        BleLink.clear();
        emit(0x123456, 99, 0, 0, 1);
        await(() -> lastCommand(2, 0x123456) != null, "orphan board collection stopped after process restart");
        check(lastCommand(1, 0) == null, "process restart does not restart navigation recording");
    }

    private static void faultReopen() throws Exception {
        await(() -> recorder.getLatestDirectory() != null, "failed recording recovered after process restart");
        Properties metadata = new Properties();
        try (FileInputStream input = new FileInputStream(new File(recorder.getLatestDirectory(), "metadata.properties"))) {
            metadata.load(input);
        }
        check("false".equals(metadata.getProperty("active")), "recovered interrupted file is closed");
        check("true".equals(metadata.getProperty("interrupted")), "recovery reports interruption");
        check("true".equals(metadata.getProperty("tailUnknown")), "recovery retains unknown tail");
        check("2".equals(metadata.getProperty("writtenSamples")) && "2".equals(metadata.getProperty("durableNext")),
                "recovery retains only actually persisted sample cursor");
        check(recorder.getMessage().contains("中断"), "recovered state is visible");
        check(lastCommand(1, 0) == null, "failed-process restart does not restart recording");
    }

    private static void interruptions() throws Exception {
        long id = start("queue overflow", false);
        final long overflowSession = id;
        ArrayBlockingQueue<kotlin.jvm.functions.Function0<kotlin.Unit>> queue = queue();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        check(queue.offer(() -> {
            entered.countDown();
            try { if(!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out"); }
            catch(InterruptedException e) { throw new AssertionError(e); }
            return kotlin.Unit.INSTANCE;
        }), "insert finite worker barrier");
        check(entered.await(3, TimeUnit.SECONDS), "disk worker reached barrier");
        for(int i=0;i<65;i++) emit(overflowSession, 5, 0, 1, 1);
        check(queue.size() == 64, "sample backlog is bounded to 64 jobs");
        recorder.navigationStopped(); // Critical event cannot be silently lost behind a full queue.
        SystemClock.advance(4000); release.countDown();
        await(() -> !snapshotUnchecked().active, "overloaded lifecycle causes explicit interrupted stop");
        check(snapshot().tailUnknown, "queue overload cannot be reported as complete");
        await(queue::isEmpty, "old sample jobs drain before next navigation");

        id = start("stop timeout", true);
        BleLink.connected = false;
        recorder.navigationStopped();
        Thread.sleep(300);
        SystemClock.advance(181000);
        await(() -> !snapshotUnchecked().active, "bounded stop drain timeout");
        check(snapshot().tailUnknown && snapshot().interrupted, "timeout is interrupted with unknown tail");
        check(snapshot().simulation, "simulation metadata is explicit");
        BleLink.connected = true;

        id = start("storage failure", false);
        final long failureSession = id;
        emit(id, 11, 0, 2, 1);
        await(() -> hasAck(failureSession, 11, 2), "pre-fault samples saved");
        // Inject the real filesystem write failure by closing the actual stream,
        // rather than replacing the production store with a fake success/failure.
        Field field = ContinuousImuStore.class.getDeclaredField("stream"); field.setAccessible(true);
        ((FileOutputStream) field.get(store())).close();
        BleLink.clear();
        emit(id, 11, 2, 2, 1);
        await(() -> recorder.getMessage().contains("保存失败"), "actual IOException reaches visible failure state");
        check(!hasAck(id, 11, 4), "failed append is never acknowledged");
        check(lastCommand(2, id) != null, "storage failure asks board to stop");
        check(sampleRows() == 2, "failed batch did not invent persisted samples");
    }

    private static long start(String title, boolean simulation) throws Exception {
        BleLink.clear(); SystemClock.advance(4000);
        recorder.navigationStarted("导航", title, simulation);
        await(() -> lastCommand(1, 0) != null, "navigation sends START");
        byte[] start = lastCommand(1, 0); long id = u32(start, 4);
        check(id != 0 && u32(start, 8) == 0 && u32(start, 12) == 0, "new session starts without fabricated cursor");
        return id;
    }

    private static void stop(long session, long boot, long next) throws Exception {
        BleLink.clear(); recorder.navigationStopped();
        await(() -> lastCommand(2, session) != null, "navigation end sends STOP");
        emit(session, boot, next, 0, 2);
        await(() -> !snapshotUnchecked().active, "stopped final count closes file");
        check(hasAck(session, boot, next), "final durable cursor acknowledged");
    }

    private static void emit(long session, long boot, long first, int count, int flags) {
        ByteBuffer b = ByteBuffer.allocate(52 + count * 32).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) session).putInt((int) boot).putInt((int) first).putInt((int) first + count).putInt((int) first);
        b.putInt(0).putInt(0).putInt(0).putInt(100000).putInt(100).putInt((flags & 2) != 0 ? 100000 : 0);
        b.putShort((short) flags).putShort((short) count);
        for (int i=0;i<count;i++) {
            b.putInt((int)(first+i)*40+100);
            for (float value : new float[]{.2f,-.4f,9.8f,-6.8f,.6f,.1f,36f}) b.putFloat(value);
        }
        CRC32 crc = new CRC32();crc.update(b.array(),0,b.capacity()-4);b.putInt((int)crc.getValue());
        byte[] frame = new byte[8 + b.capacity()];
        ByteBuffer f = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        f.put((byte)0xd7).put((byte)0x52).put((byte)1).put((byte)1).put((byte)0).put((byte)1).putShort((short)b.capacity()).put(b.array());
        check(recorder.onNotification(frame,SystemClock.elapsedRealtime()), "binary recording notification consumed");
    }

    private static boolean hasAck(long session,long boot,long next) {
        return BleLink.commands().stream().anyMatch(p -> p[3]==3 && u32(p,4)==session && u32(p,8)==boot && u32(p,12)==next);
    }
    private static byte[] lastCommand(int opcode,long session) {
        List<byte[]> list=BleLink.commands();
        for(int i=list.size()-1;i>=0;i--) {
            byte[] p=list.get(i);
            if(p[3]==opcode && (session==0 || u32(p,4)==session)) return p;
        }
        return null;
    }
    private static long u32(byte[] bytes,int offset) { return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)); }
    private static ContinuousImuStore store() throws Exception {
        Field field=ContinuousImuRecorder.class.getDeclaredField("store");field.setAccessible(true);
        return (ContinuousImuStore)field.get(null);
    }
    @SuppressWarnings("unchecked")
    private static ArrayBlockingQueue<kotlin.jvm.functions.Function0<kotlin.Unit>> queue() throws Exception {
        Field field=ContinuousImuRecorder.class.getDeclaredField("jobs");field.setAccessible(true);
        return (ArrayBlockingQueue<kotlin.jvm.functions.Function0<kotlin.Unit>>)field.get(null);
    }
    private static ContinuousImuStore.Stats snapshot() throws Exception { return store().snapshot(); }
    private static ContinuousImuStore.Stats snapshotUnchecked() {
        try { return snapshot(); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static long sampleRows() throws Exception {
        try(java.util.stream.Stream<String> lines=java.nio.file.Files.lines(new File(recorder.getLatestDirectory(),"samples.csv").toPath())) {
            return lines.filter(line->line.startsWith("S,")).count();
        }
    }
    private static void await(BooleanSupplier condition,String message) throws Exception {
        long until=System.nanoTime()+10000000000L;
        while(!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(20);
        check(condition.getAsBoolean(),message);
    }
    private static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);assertions++;
    }
}
