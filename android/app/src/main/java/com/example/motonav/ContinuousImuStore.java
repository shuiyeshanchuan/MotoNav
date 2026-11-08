package com.example.motonav;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Single-worker, append-only IMU journal. append() returns an ACK cursor only after fsync.
 * The journal is authoritative; properties are an atomic, human-readable summary. Recovery
 * validates each line's CRC and sequence, truncates a torn suffix, then rebuilds the cursor.
 * No Android/JSON dependency, accident algorithm, unbounded sample list or history deletion.
 */
public final class ContinuousImuStore {
    public static final long DEFAULT_BUDGET_BYTES = 512L * 1024 * 1024;
    public static final long DEFAULT_MINIMUM_FREE_BYTES = 64L * 1024 * 1024;
    private static final String HEADER = "# MotoNav continuous IMU journal v1\n" +
            "# S,boot,segment,sequence,board_ms,received_wall_ms,received_monotonic_ms,ax_mps2,ay_mps2,az_mps2,gx_dps,gy_dps,gz_dps,temp_c,record_crc32\n";
    private static final String README = "MotoNav continuous IMU v1\n" +
            "samples.csv is append-only UTF-8/ASCII. Last field is CRC32 of the preceding line text.\n" +
            "S rows follow the CSV header; board_ms is unsigned 32-bit and may wrap.\n" +
            "B,boot,segment,wall_ms,monotonic_ms starts a segment; sequence restarts at zero.\n" +
            "G,boot,segment,from_seq,to_seq records missing [from_seq,to_seq), not invented samples.\n" +
            "T,boot,segment,durable_next,overflow,missed_slots,read_errors,flags,board_now,start_ms,stop_ms,wall_ms,monotonic_ms records board status.\n" +
            "D,count records retransmitted sample duplicates. E,key_base64,value_base64 records annotations.\n" +
            "F,reason_base64,end_wall_ms,end_monotonic_ms,tail_unknown records termination.\n" +
            "metadata.properties summarizes all segments. gapSamples counts confirmed sequence gaps;\n" +
            "overflow/missedSlots/readErrors sum board counter increases per segment. These can overlap.\n" +
            "tailUnknown=true means the final unsent extent is unknown, never zero presumed loss.\n" +
            "Simulation is explicitly labeled. Data is raw evidence, not accident detection or a hardware accuracy claim.\n";

    public static final class Stats {
        public final long session, boot, durableNext, writtenSamples, gapSamples;
        public final long overflow, missedSlots, readErrors, duplicateSamples;
        public final long startWallMs, endWallMs, startMonotonicMs, endMonotonicMs, segments, events;
        public final boolean active, interrupted, tailUnknown, simulation;
        public final String mode, title, reason;
        public final File directory;
        private Stats(State s) {
            session=s.session; boot=s.boot; durableNext=s.durableNext; writtenSamples=s.writtenSamples;
            gapSamples=s.gapSamples; overflow=s.overflow; missedSlots=s.missedSlots; readErrors=s.readErrors;
            duplicateSamples=s.duplicateSamples; startWallMs=s.startWallMs; endWallMs=s.endWallMs;
            startMonotonicMs=s.startMonotonicMs; endMonotonicMs=s.endMonotonicMs; segments=s.segments;
            events=s.events; active=s.active; interrupted=s.interrupted; tailUnknown=s.tailUnknown;
            simulation=s.simulation; mode=s.mode; title=s.title; reason=s.reason; directory=s.directory;
        }
    }

    private static final class State {
        long session,boot,durableNext,writtenSamples,gapSamples,overflow,missedSlots,readErrors,duplicateSamples;
        long startWallMs,endWallMs,startMonotonicMs,endMonotonicMs,segments,events;
        long segmentOverflow,segmentMissed,segmentErrors;
        boolean active=true,interrupted,tailUnknown,simulation;
        String mode,title,reason="recording",timezone;
        File directory;
        State copy() {
            State s=new State(); s.session=session;s.boot=boot;s.durableNext=durableNext;s.writtenSamples=writtenSamples;
            s.gapSamples=gapSamples;s.overflow=overflow;s.missedSlots=missedSlots;s.readErrors=readErrors;
            s.duplicateSamples=duplicateSamples;s.startWallMs=startWallMs;s.endWallMs=endWallMs;
            s.startMonotonicMs=startMonotonicMs;s.endMonotonicMs=endMonotonicMs;s.segments=segments;s.events=events;
            s.segmentOverflow=segmentOverflow;s.segmentMissed=segmentMissed;s.segmentErrors=segmentErrors;
            s.active=active;s.interrupted=interrupted;s.tailUnknown=tailUnknown;s.simulation=simulation;
            s.mode=mode;s.title=title;s.reason=reason;s.timezone=timezone;s.directory=directory;return s;
        }
    }

    private final File root;
    private final long budgetBytes,minimumFreeBytes;
    private long usedBytes;
    private State state;
    private FileOutputStream stream;
    private boolean failed;
    // One bounded sidecar buffer, also used for late callbacks to closed journeys.
    private static final int TRACE_BUFFER_BYTES = 64 * 1024;
    private static final int TRACE_LINE_BYTES = 8192;
    private final StringBuilder traceBuffer = new StringBuilder(4096);
    private File traceDirectory;
    private static final Set<String> TRACE_FIELDS = new HashSet<>(Arrays.asList(
            "state","reason","source","seconds","real_sms","armed","navigation","simulation","mode",
            "boot","sequence","flags","age_ms","part","total","result","delivery","previous_state",
            "remaining_ms","deadline_monotonic_ms","start_monotonic_ms","observed_monotonic_ms","coalesced_changes",
            "dynamic_mps2","speed_drop_kmh","deceleration_mps2","imu_at_ms","gps_at_ms",
            "fix_wall_ms","received_wall_ms","fix_monotonic_ms","received_monotonic_ms","accuracy_m","speed_kmh",
            "dynamic_threshold_mps2","dynamic_duration_ms","max_gps_window_ms","max_imu_age_ms","max_gps_age_ms","evidence_window_ms",
            "app_version","detector_version","trace_version","firmware","firmware_complete","screen",
            "reference_state","reference_revision","reference_x","reference_y","reference_z","reference_samples","reference_reason",
            "connected","verified","ota","continuous_supported","queue_rejected","protocol_errors"));
    private static final String TRACE_HEADER = "# MotoNav journey trace v1; independent extension to samples.csv schema 1\n" +
            "# Every data line ends in CRC32 of the preceding UTF-8 text; text values use base64.\n" +
            "# H,session,boot,first_seq,wire_next_seq,oldest_seq,count,board_now,flags,overflow,missed_slots,read_errors,start_ms,stop_ms,rx_wall_ms,rx_mono_ms,crc\n" +
            "# W,session,boot,batch_first_seq,index,sequence,board_ms,ax,ay,az,gx,gy,gz,temp,crc (includes retransmitted duplicates)\n" +
            "# P,lat,lng,instant_kmh,accuracy_m,fix_wall_ms,rx_wall_ms,rx_mono_ms,age_ms,fix_mono_ms,real,simulation,source_base64,crc\n" +
            "# E,kind_base64,token,wall_ms,mono_ms,key,value_base64,...,crc\n" +
            "# H and its count W rows form one received batch. Only complete groups replay as batches.\n" +
            "# GPS coordinates are source-native (current navigation sources use GCJ-02); fix time is not receive time.\n" +
            "# Noncritical GPS/events flush each worker tick (normally 1s); queue delays/process death can lose an unsynced suffix. No contacts or message bodies.\n";

    public ContinuousImuStore(File root) throws IOException {
        this(root,DEFAULT_BUDGET_BYTES,DEFAULT_MINIMUM_FREE_BYTES);
    }
    public ContinuousImuStore(File root,long budgetBytes,long minimumFreeBytes) throws IOException {
        if(root==null || budgetBytes<=0 || minimumFreeBytes<0) throw new IllegalArgumentException("invalid IMU storage limits");
        this.root=root.getCanonicalFile();this.budgetBytes=budgetBytes;this.minimumFreeBytes=minimumFreeBytes;
        if(!this.root.isDirectory() && !this.root.mkdirs()) throw new IOException("无法创建 IMU 记录目录");
        usedBytes=directorySize(this.root);
    }

    public void start(long session,String mode,String title,boolean simulation,long wallMs,long monotonicMs) throws IOException {
        if(session<=0 || session>ContinuousImuProtocol.UINT32_MAX || wallMs<0 || monotonicMs<0 || mode==null || title==null)
            throw new IllegalArgumentException("invalid IMU session metadata");
        if(state!=null && state.active) throw new IllegalStateException("previous IMU session still active");
        flushTrace();
        checkStorage(4096);
        String day=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(wallMs));
        String time=new SimpleDateFormat("HHmmss",Locale.ROOT).format(new Date(wallMs));
        File directory=new File(new File(root,day),time+"_"+UUID.randomUUID());
        if(!directory.mkdirs()) throw new IOException("无法创建 IMU 会话目录");
        State s=new State();s.session=session;s.mode=mode.substring(0,Math.min(120,mode.length()));
        s.title=title.substring(0,Math.min(120,title.length()));s.simulation=simulation;s.startWallMs=wallMs;
        s.startMonotonicMs=monotonicMs;s.directory=directory;s.timezone=TimeZone.getDefault().getID();
        state=s;failed=false;
        try {
            saveMetadata(s);
            byte[] readme=README.getBytes(StandardCharsets.UTF_8);
            writeSynced(new File(directory,"README.txt"),readme);usedBytes+=readme.length;
            stream=new FileOutputStream(new File(directory,"samples.csv"),true);
            writeJournal(HEADER.getBytes(StandardCharsets.UTF_8));
            selectTrace(directory);
            flushTrace();
        } catch(IOException e) { poison();throw e; }
    }

    /** A cumulative durable cursor for this batch's board boot; never ACK before this returns. */
    public long append(ContinuousImuProtocol.Batch batch,long wallMs,long receivedMonotonicMs) throws IOException {
        requireActive();
        if(batch==null || batch.session!=state.session || wallMs<0 || receivedMonotonicMs<0)
            throw new IllegalArgumentException("IMU batch belongs to another session");
        State next=state.copy();StringBuilder out=new StringBuilder(4096);
        if(next.segments==0 || next.boot!=batch.boot) {
            if(next.segments>0) { next.tailUnknown=true;next.interrupted=true; }
            next.segments++;next.boot=batch.boot;next.durableNext=0;
            next.segmentOverflow=next.segmentMissed=next.segmentErrors=0;
            line(out,"B,"+next.boot+","+next.segments+","+wallMs+","+receivedMonotonicMs);
        }
        if(batch.firstSeq>next.durableNext) {
            line(out,"G,"+next.boot+","+next.segments+","+next.durableNext+","+batch.firstSeq);
            next.gapSamples+=batch.firstSeq-next.durableNext;next.durableNext=batch.firstSeq;
        }
        long duplicates=Math.min(batch.samples.size(),Math.max(0,next.durableNext-batch.firstSeq));
        if(duplicates>0) { next.duplicateSamples+=duplicates;line(out,"D,"+duplicates); }
        for(int i=(int)duplicates;i<batch.samples.size();i++) {
            ContinuousImuProtocol.Sample sample=batch.samples.get(i);
            long sequence=batch.firstSeq+i;
            if(sequence!=next.durableNext) throw new IllegalArgumentException("noncontiguous IMU batch");
            line(out,"S,"+next.boot+","+next.segments+","+sequence+","+sample.timestamp+","+wallMs+","+receivedMonotonicMs+","+
                    sample.ax+","+sample.ay+","+sample.az+","+sample.gx+","+sample.gy+","+sample.gz+","+sample.temperature);
            next.durableNext++;next.writtenSamples++;
        }
        updateCounters(next,batch.overflow,batch.missedSlots,batch.readErrors);
        next.endWallMs=wallMs;next.endMonotonicMs=receivedMonotonicMs;
        line(out,"T,"+next.boot+","+next.segments+","+next.durableNext+","+batch.overflow+","+batch.missedSlots+","+batch.readErrors+","+
                batch.flags+","+batch.boardNow+","+batch.startMillis+","+batch.stopMillis+","+wallMs+","+receivedMonotonicMs);
        try {
            traceBatch(batch,wallMs,receivedMonotonicMs);
            flushTrace(); // Both the original wire evidence and deduplicated journal must be durable before ACK.
            writeJournal(out.toString().getBytes(StandardCharsets.UTF_8));
            state=next;saveMetadata(next);
            return next.durableNext;
        } catch(IOException e) { poison();throw e; }
    }

    public void note(String key,String value) throws IOException {
        requireActive();
        if(key==null || value==null || key.length()>64 || value.length()>512) throw new IllegalArgumentException("IMU annotation too long");
        StringBuilder out=new StringBuilder();line(out,"E,"+base64(key)+","+base64(value));
        try { writeJournal(out.toString().getBytes(StandardCharsets.UTF_8));state.events++;saveMetadata(state); }
        catch(IOException e) { poison();throw e; }
    }

    public void stop(String reason,long wallMs,long monotonicMs) throws IOException {
        if(state==null || !state.active) return;
        if(failed) throw new IOException("IMU 保存已失败；未完成文件将在下次启动时恢复");
        if(reason==null || reason.length()>256 || wallMs<0 || monotonicMs<0) throw new IllegalArgumentException("invalid IMU stop metadata");
        State next=state.copy();next.active=false;next.reason=reason;next.endWallMs=wallMs;next.endMonotonicMs=monotonicMs;
        next.interrupted=next.interrupted || !reason.equals("navigation_ended") && !reason.equals("completed");
        next.tailUnknown=next.tailUnknown || next.interrupted;
        StringBuilder out=new StringBuilder();line(out,"F,"+base64(reason)+","+wallMs+","+monotonicMs+","+next.tailUnknown);
        try { flushTrace();writeJournal(out.toString().getBytes(StandardCharsets.UTF_8));saveMetadata(next);stream.close();stream=null;state=next; }
        catch(IOException e) { poison();throw e; }
    }

    public Stats snapshot() {
        if(state==null) throw new IllegalStateException("no IMU session");
        return new Stats(state);
    }

    /** A closed diagnostic session can coexist with the board tail still draining to an older ride. */
    public File diagnosticSession(long session,long wall,long mono) throws IOException {
        if(session<=0 || session>ContinuousImuProtocol.UINT32_MAX || wall<0 || mono<0) throw new IllegalArgumentException("invalid diagnostic session");
        flushTrace();checkStorage(4096);
        String day=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(wall));
        String time=new SimpleDateFormat("HHmmss",Locale.ROOT).format(new Date(wall));
        File directory=new File(new File(root,day),time+"_"+UUID.randomUUID());
        if(!directory.mkdirs()) throw new IOException("无法创建诊断会话目录");
        State diagnostic=new State();diagnostic.session=session;diagnostic.mode="diagnostic_only";diagnostic.title="";
        diagnostic.reason="diagnostic_only";diagnostic.active=false;diagnostic.directory=directory;
        diagnostic.startWallMs=diagnostic.endWallMs=wall;diagnostic.startMonotonicMs=diagnostic.endMonotonicMs=mono;
        diagnostic.timezone=TimeZone.getDefault().getID();saveMetadata(diagnostic);
        StringBuilder journal=new StringBuilder(HEADER);line(journal,"F,"+base64("diagnostic_only")+","+wall+","+mono+",false");
        byte[] data=journal.toString().getBytes(StandardCharsets.UTF_8);checkStorage(data.length);
        writeSynced(new File(directory,"samples.csv"),data);usedBytes+=data.length;
        byte[] readme=README.getBytes(StandardCharsets.UTF_8);checkStorage(readme.length);writeSynced(new File(directory,"README.txt"),readme);usedBytes+=readme.length;
        selectTrace(directory);return directory;
    }

    /** Close unfinished old files as interrupted. This never restarts navigation or sampling. */
    public List<Stats> recoverUnfinished() throws IOException {
        if(state!=null && state.active) throw new IllegalStateException("cannot recover while recording");
        List<Stats> recovered=new ArrayList<>();
        File[] days=root.listFiles(File::isDirectory);
        if(days==null) return recovered;
        for(File day:days) {
            File[] sessions=day.listFiles(File::isDirectory);if(sessions==null) continue;
            for(File directory:sessions) {
                File meta=new File(directory,"metadata.properties");if(!meta.isFile()) continue;
                File trace=new File(directory,"journey.csv");
                if(trace.isFile()) { long size=trace.length();recoverTrace(trace);usedBytes-=size-trace.length(); }
                Properties p=new Properties();try(FileInputStream input=new FileInputStream(meta)) { p.load(input); }
                if(!Boolean.parseBoolean(p.getProperty("active","false"))) continue;
                State s=initialMetadata(p,directory);
                File journal=new File(directory,"samples.csv");
                long oldSize=journal.length();
                s=rebuildJournal(s,journal);
                usedBytes-=oldSize-journal.length();
                if(s.active) {
                    s.active=false;s.interrupted=true;s.tailUnknown=true;s.reason="interrupted";
                    StringBuilder out=new StringBuilder();line(out,"F,"+base64(s.reason)+","+s.endWallMs+","+s.endMonotonicMs+",true");
                    byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);checkStorage(bytes.length);
                    try(FileOutputStream output=new FileOutputStream(journal,true)) { output.write(bytes);output.getFD().sync(); }
                    usedBytes+=bytes.length;
                }
                saveMetadata(s);recovered.add(new Stats(s));
            }
        }
        recovered.sort((a,b)->Long.compare(a.startWallMs,b.startWallMs));
        return recovered;
    }

    /** Appends source fixes, including invalid/stale fixes, without silently replacing speed by a trip maximum. */
    public void traceGps(File directory,double lat,double lng,double kmh,double accuracy,long fixWall,
                         long receivedWall,long receivedMono,boolean simulation,String source) throws IOException {
        if(source==null || source.length()>64 || receivedWall<0 || receivedMono<0) throw new IllegalArgumentException("invalid GPS trace");
        selectTrace(directory);
        StringBuilder out=new StringBuilder();
        long age=receivedWall-fixWall;
        line(out,"P,"+lat+","+lng+","+kmh+","+accuracy+","+fixWall+","+receivedWall+","+receivedMono+","+age+","+(receivedMono-age)+","+!simulation+","+simulation+","+base64(source));
        bufferTrace(out.toString());
    }

    /** Strict keys keep contact identities, SMS text and UI prompts out of exported diagnostic packages. */
    public void traceEvent(File directory,String kind,long token,Map<String,String> fields,long wall,long mono,boolean durable) throws IOException {
        if(kind==null || !kind.matches("[a-z][a-z0-9_]{0,63}") || token<0 || wall<0 || mono<0 || fields==null)
            throw new IllegalArgumentException("invalid journey event");
        selectTrace(directory);
        StringBuilder body=new StringBuilder("E,"+base64(kind)+","+token+","+wall+","+mono);
        int count=0;
        for(Map.Entry<String,String> field:fields.entrySet()) {
            if(!TRACE_FIELDS.contains(field.getKey()) || field.getValue()==null) continue;
            if(++count>40) throw new IllegalArgumentException("too many journey fields");
            String value=field.getValue();
            if(value.length()>256) value=value.substring(0,256);
            body.append(',').append(field.getKey()).append(',').append(base64(value));
        }
        StringBuilder out=new StringBuilder();line(out,body.toString());
        if(out.length()>TRACE_LINE_BYTES) throw new IllegalArgumentException("journey event too long");
        bufferTrace(out.toString());
        if(durable) flushTrace();
    }

    /** Durable small on-disk routing index: no unbounded token map and no personal/contact data. */
    public void bindEventToken(long token,File directory) throws IOException {
        if(token<=0) throw new IllegalArgumentException("invalid event token");
        File safe=checkedDirectory(directory),index=new File(root,"event_index");
        if(!index.isDirectory() && !index.mkdirs()) throw new IOException("无法创建事件索引");
        File destination=new File(index,token+".ref");
        if(destination.exists()) {
            if(!safe.equals(eventDirectory(token))) throw new IOException("事件编号已属于另一段行程");
            return;
        }
        String relative=root.toPath().relativize(safe.toPath()).toString().replace('\\','/');
        StringBuilder out=new StringBuilder();line(out,relative);byte[] data=out.toString().getBytes(StandardCharsets.UTF_8);
        checkStorage(data.length);File temporary=new File(index,token+".ref.tmp");writeSynced(temporary,data);
        atomicReplace(temporary,destination);usedBytes+=data.length;
    }

    public File eventDirectory(long token) throws IOException {
        if(token<=0) return null;
        File ref=new File(new File(root,"event_index"),token+".ref");
        if(!ref.isFile()) return null;
        if(ref.length()>512) throw new IOException("事件索引损坏");
        byte[] data=Files.readAllBytes(ref.toPath());String text=new String(data,StandardCharsets.UTF_8);
        if(!text.endsWith("\n")) throw new IOException("事件索引未完成");
        text=text.substring(0,text.length()-1);int comma=text.lastIndexOf(',');
        if(comma<1 || !text.substring(comma+1).equals(crcText(text.substring(0,comma)))) throw new IOException("事件索引校验失败");
        return checkedDirectory(new File(root,text.substring(0,comma)));
    }

    /** Called on the same FIFO worker every second, before export and before every ACK. */
    public void flushTrace() throws IOException {
        if(traceBuffer.length()==0) return;
        byte[] data=traceBuffer.toString().getBytes(StandardCharsets.UTF_8);checkStorage(data.length);
        try(FileOutputStream output=new FileOutputStream(new File(traceDirectory,"journey.csv"),true)) {
            output.write(data);output.getFD().sync();
        }
        usedBytes+=data.length;traceBuffer.setLength(0);
    }

    public File checkedDirectory(File directory) throws IOException {
        if(directory==null) throw new IOException("没有行程目录");
        File safe=directory.getCanonicalFile();
        if(!safe.toPath().startsWith(root.toPath()) || safe.equals(root) || !new File(safe,"metadata.properties").isFile())
            throw new IOException("无效的行程目录");
        return safe;
    }

    private void selectTrace(File directory) throws IOException {
        File safe=checkedDirectory(directory);
        if(safe.equals(traceDirectory)) return;
        flushTrace();traceDirectory=safe;
        File file=new File(safe,"journey.csv");
        if(file.exists()) { long size=file.length();recoverTrace(file);usedBytes-=size-file.length(); }
        else { byte[] bytes=TRACE_HEADER.getBytes(StandardCharsets.UTF_8);checkStorage(bytes.length);writeSynced(file,bytes);usedBytes+=bytes.length; }
    }
    private void bufferTrace(String text) throws IOException {
        if(text.length()>TRACE_BUFFER_BYTES) throw new IllegalArgumentException("oversized trace batch");
        if(traceBuffer.length()+text.length()>TRACE_BUFFER_BYTES) flushTrace();
        checkStorage(traceBuffer.length()+text.length());traceBuffer.append(text);
    }
    private void traceBatch(ContinuousImuProtocol.Batch b,long wall,long mono) throws IOException {
        selectTrace(state.directory);StringBuilder out=new StringBuilder(4096);
        line(out,"H,"+b.session+","+b.boot+","+b.firstSeq+","+b.nextSeq+","+b.oldestSeq+","+b.samples.size()+","+b.boardNow+","+b.flags+","+
                b.overflow+","+b.missedSlots+","+b.readErrors+","+b.startMillis+","+b.stopMillis+","+wall+","+mono);
        for(int i=0;i<b.samples.size();i++) {
            ContinuousImuProtocol.Sample s=b.samples.get(i);
            line(out,"W,"+b.session+","+b.boot+","+b.firstSeq+","+i+","+(b.firstSeq+i)+","+s.timestamp+","+
                    s.ax+","+s.ay+","+s.az+","+s.gx+","+s.gy+","+s.gz+","+s.temperature);
        }
        bufferTrace(out.toString());
    }
    private static void recoverTrace(File file) throws IOException {
        try(RandomAccessFile input=new RandomAccessFile(file,"rw")) {
            long good=0,batchStart=-1;int remaining=0;
            while(input.getFilePointer()<input.length()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(256);boolean terminated=false;
                while(bytes.size()<=TRACE_LINE_BYTES) { int ch=input.read();if(ch<0) break;if(ch=='\n') { terminated=true;break; }bytes.write(ch); }
                if(!terminated) break;
                String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
                if(text.startsWith("#") && remaining==0) { good=input.getFilePointer();continue; }
                int comma=text.lastIndexOf(',');if(comma<=0 || !text.substring(comma+1).equals(crcText(text.substring(0,comma)))) break;
                String[] p=text.substring(0,comma).split(",",-1);
                try {
                    if(remaining>0) { if(!p[0].equals("W") || p.length!=14) break;remaining--; }
                    else if(p[0].equals("H") && p.length==16) {
                        int count=Integer.parseInt(p[6]);if(count<0 || count>8) break;
                        batchStart=good;remaining=count;
                    } else if(p[0].equals("W") || !(p[0].equals("P") && p.length==13 || p[0].equals("E") && p.length>=5 && p.length%2==1)) break;
                } catch(RuntimeException e) { break; }
                if(remaining==0) { good=input.getFilePointer();batchStart=-1; }
            }
            if(batchStart>=0) good=batchStart;
            if(input.length()!=good) { input.setLength(good);input.getFD().sync(); }
        }
    }

    private static void atomicReplace(File temporary,File destination) throws IOException {
        try { Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    }

    private static State initialMetadata(Properties p,File directory) throws IOException {
        try {
            if(!"1".equals(p.getProperty("schema"))) throw new IllegalArgumentException();
            State s=new State();s.session=Long.parseLong(p.getProperty("session"));
            if(s.session<=0 || s.session>ContinuousImuProtocol.UINT32_MAX) throw new IllegalArgumentException();
            s.mode=p.getProperty("mode","");s.title=p.getProperty("title","");s.timezone=p.getProperty("timezone","");
            s.simulation=Boolean.parseBoolean(p.getProperty("simulation","false"));
            s.startWallMs=Long.parseLong(p.getProperty("startWallMs"));s.startMonotonicMs=Long.parseLong(p.getProperty("startMonotonicMs"));
            s.endWallMs=s.startWallMs;s.endMonotonicMs=s.startMonotonicMs;s.directory=directory;return s;
        } catch(RuntimeException e) { throw new IOException("IMU 会话元数据损坏："+directory.getName(),e); }
    }

    private static State rebuildJournal(State s,File file) throws IOException {
        try(RandomAccessFile input=new RandomAccessFile(file,"rw")) {
            long goodEnd=0;
            while(input.getFilePointer()<input.length()) {
                byte[] bytes=readLine(input);
                if(bytes==null) break;
                String text=new String(bytes,StandardCharsets.US_ASCII);
                if(text.startsWith("#")) { goodEnd=input.getFilePointer();continue; }
                int comma=text.lastIndexOf(',');if(comma<=0) break;
                String body=text.substring(0,comma),sum=text.substring(comma+1);
                if(!sum.equals(crcText(body))) break;
                State next=s.copy();
                try { if(!s.active) break;applyRecord(next,body.split(",",-1)); }
                catch(RuntimeException e) { break; }
                s=next;
                goodEnd=input.getFilePointer();
            }
            if(input.length()!=goodEnd) { input.setLength(goodEnd);input.getFD().sync(); }
        }
        return s;
    }

    private static byte[] readLine(RandomAccessFile input) throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream(256);
        while(line.size()<=4096) {
            int value=input.read();if(value<0) return null;if(value=='\n') return line.toByteArray();line.write(value);
        }
        return null;
    }

    private static void applyRecord(State s,String[] p) {
        switch(p[0]) {
            case "B":
                if(p.length!=5 || Long.parseLong(p[2])!=s.segments+1) throw new IllegalArgumentException();
                if(s.segments>0) { s.tailUnknown=true;s.interrupted=true; }
                s.boot=Long.parseLong(p[1]);s.segments++;s.durableNext=0;
                s.segmentOverflow=s.segmentMissed=s.segmentErrors=0;
                s.endWallMs=Long.parseLong(p[3]);s.endMonotonicMs=Long.parseLong(p[4]);break;
            case "G":
                sameSegment(s,p);if(p.length!=5 || Long.parseLong(p[3])!=s.durableNext) throw new IllegalArgumentException();
                long to=Long.parseLong(p[4]);if(to<s.durableNext) throw new IllegalArgumentException();
                s.gapSamples+=to-s.durableNext;s.durableNext=to;break;
            case "S":
                sameSegment(s,p);if(p.length!=14 || Long.parseLong(p[3])!=s.durableNext) throw new IllegalArgumentException();
                Long.parseLong(p[4]);for(int i=7;i<14;i++) if(!Float.isFinite(Float.parseFloat(p[i]))) throw new IllegalArgumentException();
                long wall=Long.parseLong(p[5]),mono=Long.parseLong(p[6]);
                s.endWallMs=wall;s.endMonotonicMs=mono;s.durableNext++;s.writtenSamples++;break;
            case "T":
                sameSegment(s,p);if(p.length!=13 || Long.parseLong(p[3])!=s.durableNext) throw new IllegalArgumentException();
                updateCounters(s,Long.parseLong(p[4]),Long.parseLong(p[5]),Long.parseLong(p[6]));
                s.endWallMs=Long.parseLong(p[11]);s.endMonotonicMs=Long.parseLong(p[12]);break;
            case "D":
                if(p.length!=2) throw new IllegalArgumentException();s.duplicateSamples+=Long.parseLong(p[1]);break;
            case "E":
                if(p.length!=3) throw new IllegalArgumentException();decode64(p[1]);decode64(p[2]);s.events++;break;
            case "F":
                if(p.length!=5) throw new IllegalArgumentException();s.reason=decode64(p[1]);
                s.endWallMs=Long.parseLong(p[2]);s.endMonotonicMs=Long.parseLong(p[3]);
                s.tailUnknown=Boolean.parseBoolean(p[4]);s.interrupted=s.tailUnknown;s.active=false;break;
            default: throw new IllegalArgumentException();
        }
    }
    private static void sameSegment(State s,String[] p) {
        if(p.length<3 || s.segments==0 || Long.parseLong(p[1])!=s.boot || Long.parseLong(p[2])!=s.segments)
            throw new IllegalArgumentException();
    }
    private static void updateCounters(State s,long overflow,long missed,long errors) {
        s.overflow+=Math.max(0,overflow-s.segmentOverflow);s.missedSlots+=Math.max(0,missed-s.segmentMissed);
        s.readErrors+=Math.max(0,errors-s.segmentErrors);s.segmentOverflow=Math.max(s.segmentOverflow,overflow);
        s.segmentMissed=Math.max(s.segmentMissed,missed);s.segmentErrors=Math.max(s.segmentErrors,errors);
    }
    private void requireActive() throws IOException {
        if(state==null || !state.active) throw new IllegalStateException("IMU session is not active");
        if(failed) throw new IOException("IMU 保存已失败，不确认未保存样本");
    }
    private void poison() { failed=true;try { if(stream!=null) stream.close(); }catch(IOException ignored){}stream=null; }
    private void checkStorage(long bytes) throws IOException {
        if(bytes<0 || usedBytes>budgetBytes-bytes) throw new IOException("IMU 记录已达 512 MiB 存储预算；已停止，未删除历史");
        if(root.getUsableSpace()<minimumFreeBytes || root.getUsableSpace()-minimumFreeBytes<bytes)
            throw new IOException("手机可用空间不足 64 MiB，已停止 IMU 保存");
    }
    private void writeJournal(byte[] bytes) throws IOException {
        checkStorage(bytes.length);stream.write(bytes);stream.getFD().sync();usedBytes+=bytes.length;
    }
    private void saveMetadata(State s) throws IOException {
        Properties p=new Properties();p.setProperty("schema","1");
        p.setProperty("mode",s.mode);p.setProperty("title",s.title);p.setProperty("timezone",s.timezone);
        p.setProperty("reason",s.reason);p.setProperty("simulation",String.valueOf(s.simulation));
        p.setProperty("active",String.valueOf(s.active));p.setProperty("interrupted",String.valueOf(s.interrupted));p.setProperty("tailUnknown",String.valueOf(s.tailUnknown));
        p.setProperty("session",String.valueOf(s.session));p.setProperty("boot",String.valueOf(s.boot));p.setProperty("durableNext",String.valueOf(s.durableNext));
        p.setProperty("writtenSamples",String.valueOf(s.writtenSamples));p.setProperty("gapSamples",String.valueOf(s.gapSamples));
        p.setProperty("overflow",String.valueOf(s.overflow));p.setProperty("missedSlots",String.valueOf(s.missedSlots));p.setProperty("readErrors",String.valueOf(s.readErrors));
        p.setProperty("duplicateSamples",String.valueOf(s.duplicateSamples));p.setProperty("segments",String.valueOf(s.segments));p.setProperty("events",String.valueOf(s.events));
        p.setProperty("startWallMs",String.valueOf(s.startWallMs));p.setProperty("endWallMs",String.valueOf(s.endWallMs));
        p.setProperty("startMonotonicMs",String.valueOf(s.startMonotonicMs));p.setProperty("endMonotonicMs",String.valueOf(s.endMonotonicMs));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();p.store(bytes,"MotoNav continuous IMU; samples.csv is authoritative");
        byte[] data=bytes.toByteArray();checkStorage(data.length);
        File destination=new File(s.directory,"metadata.properties"),temporary=new File(s.directory,"metadata.properties.tmp");
        long previous=destination.length();writeSynced(temporary,data);
        try { Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING); }
        usedBytes+=data.length-previous;
    }
    private static void writeSynced(File file,byte[] bytes) throws IOException {
        try(FileOutputStream output=new FileOutputStream(file)) { output.write(bytes);output.getFD().sync(); }
    }
    private static long directorySize(File directory) throws IOException {
        try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(directory.toPath())) {
            final long[] total={0};
            paths.filter(Files::isRegularFile).forEach(path->{try { total[0]+=Files.size(path); }catch(IOException e){throw new java.io.UncheckedIOException(e);}});
            return total[0];
        } catch(java.io.UncheckedIOException e) { throw e.getCause(); }
    }
    private static void line(StringBuilder out,String body) { out.append(body).append(',').append(crcText(body)).append('\n'); }
    private static String crcText(String text) {
        CRC32 crc=new CRC32();crc.update(text.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT,"%08x",crc.getValue());
    }
    private static String base64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode64(String value) { return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8); }
}
