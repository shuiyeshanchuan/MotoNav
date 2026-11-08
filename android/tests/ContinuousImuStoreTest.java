package com.example.motonav;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.CRC32;

/** Real fsync/journal tests with synthetic samples; no connected device required. */
public final class ContinuousImuStoreTest {
    private static int checks;
    private static final long WALL=1793851200000L;
    private static void check(boolean pass,String text) { checks++;if(!pass)throw new AssertionError(text); }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void fails(Checked task,Class<? extends Exception> type,String text) throws Exception {
        try { task.run();throw new AssertionError(text); }
        catch(Exception e) { if(!type.isInstance(e))throw e;checks++; }
    }
    private static ContinuousImuProtocol.Batch batch(long session,long boot,long first,int count,long next,
                                                     long overflow,long missed,long errors,int flags) {
        return ContinuousImuProtocolTest.batch(session,boot,first,count,next,overflow,missed,errors,flags);
    }
    private static Properties properties(File directory) throws IOException {
        Properties p=new Properties();try(FileInputStream in=new FileInputStream(new File(directory,"metadata.properties"))) { p.load(in); }return p;
    }
    private static void metadata(File directory,String key,String value) throws IOException {
        Properties p=properties(directory);p.setProperty(key,value);
        try(FileOutputStream out=new FileOutputStream(new File(directory,"metadata.properties"))) { p.store(out,"test simulated stale summary");out.getFD().sync(); }
    }
    private static File onlySession(File root) {
        File[] days=root.listFiles(File::isDirectory);check(days!=null && days.length==1,"one dated directory");
        File[] sessions=days[0].listFiles(File::isDirectory);check(sessions!=null && sessions.length==1,"one session directory");return sessions[0];
    }
    private static String line(String body) {
        CRC32 crc=new CRC32();crc.update(body.getBytes(StandardCharsets.UTF_8));
        return body+","+String.format(Locale.ROOT,"%08x",crc.getValue())+"\n";
    }
    private static void appendText(File file,String text) throws IOException {
        try(FileOutputStream out=new FileOutputStream(file,true)) { out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync(); }
    }
    private static void basic(File root) throws Exception {
        ContinuousImuStore s=new ContinuousImuStore(new File(root,"basic"),32L*1024*1024,0);
        fails(s::snapshot,IllegalStateException.class,"snapshot before start");
        s.start(123,"导航","中文目的地",true,WALL,100);
        check(s.snapshot().active && s.snapshot().simulation,"explicit simulation metadata");
        File directory=s.snapshot().directory;
        check(directory.getParentFile().getName().matches("\\d{4}-\\d{2}-\\d{2}"),"dated session");
        check(s.append(batch(123,77,0,8,8,2,4,1,1),WALL+1,101)==8,"durable first batch cursor");
        check(s.append(batch(123,77,0,8,8,2,4,1,1),WALL+2,102)==8,"retransmit does not advance cursor");
        check(s.snapshot().writtenSamples==8 && s.snapshot().duplicateSamples==8,"retransmit counted once");
        check(s.append(batch(123,77,4,8,12,2,4,1,1),WALL+3,103)==12,"overlap appends only new samples");
        check(s.snapshot().writtenSamples==12 && s.snapshot().duplicateSamples==12,"partial overlap accounting");
        check(s.append(batch(123,77,15,2,17,3,5,2,1),WALL+4,104)==17,"explicit sequence gap accepted");
        check(s.snapshot().gapSamples==3 && s.snapshot().writtenSamples==14,"gap is not invented data");
        s.append(batch(123,77,15,2,17,1,2,0,1),WALL+5,105);
        s.append(batch(123,77,17,1,18,4,7,3,1),WALL+6,106);
        check(s.snapshot().overflow==4 && s.snapshot().missedSlots==7 && s.snapshot().readErrors==3,"regressed replay counters preserve high water");
        fails(()->s.append(batch(124,77,18,1,19,0,0,0,1),WALL+7,107),IllegalArgumentException.class,"foreign session rejected");
        check(s.snapshot().durableNext==18,"foreign batch leaves cursor unchanged");
        s.note("screen","熄屏，蓝牙保持连接");
        s.stop("navigation_ended",WALL+10,110);
        ContinuousImuStore.Stats stats=s.snapshot();
        check(!stats.active && !stats.interrupted && !stats.tailUnknown && stats.events==1,"clean stop metadata");
        Properties p=properties(directory);
        check(p.getProperty("title").equals("中文目的地") && p.getProperty("simulation").equals("true"),"Unicode properties preserved");
        String content=Files.readString(new File(directory,"samples.csv").toPath());
        check(content.contains(",-7.0,") && content.contains(Base64.getEncoder().encodeToString("熄屏，蓝牙保持连接".getBytes(StandardCharsets.UTF_8))),"raw gyro and Unicode annotation retained");
        fails(()->s.append(batch(123,77,18,1,19,0,0,0,1),WALL+11,111),IllegalStateException.class,"no writes after closed session");
        check(new ContinuousImuStore(new File(root,"basic"),32L*1024*1024,0).recoverUnfinished().isEmpty(),"clean session not recovered twice");
        s.start(124,"导航","第二段",false,WALL,200);
        check(!s.snapshot().directory.equals(directory),"same second sessions have unique directories");s.stop("completed",WALL,200);

        ContinuousImuStore reset=new ContinuousImuStore(new File(root,"boot"),32L*1024*1024,0);
        reset.start(456,"导航","重启测试",false,WALL,0);
        reset.append(batch(456,1,0,8,8,1,2,3,1),WALL,1);
        reset.append(batch(456,2,3,2,5,2,4,5,1),WALL+1,2);
        check(reset.snapshot().segments==2 && reset.snapshot().durableNext==5,"board reset starts new sequence segment");
        check(reset.snapshot().gapSamples==3 && reset.snapshot().writtenSamples==10,"new boot gap accounted independently");
        check(reset.snapshot().overflow==3 && reset.snapshot().missedSlots==6 && reset.snapshot().readErrors==8,"board counters summed across boots");
        reset.stop("navigation_ended",WALL+2,3);
        check(reset.snapshot().tailUnknown && reset.snapshot().interrupted,"normal end cannot erase reboot unknown tail");

        ContinuousImuStore status=new ContinuousImuStore(new File(root,"status"),32L*1024*1024,0);
        status.start(457,"导航","状态帧",false,WALL,0);
        check(status.append(batch(457,9,10,0,10,10,0,0,2),WALL+1,1)==10,"empty status can confirm fully overwritten range");
        check(status.snapshot().writtenSamples==0 && status.snapshot().gapSamples==10,"empty status records gap only");status.stop("completed",WALL+2,2);

        File regular=new File(root,"not-a-directory");Files.writeString(regular.toPath(),"x");
        fails(()->new ContinuousImuStore(regular,8192,0),IOException.class,"regular file root rejected");
        ContinuousImuStore lowFree=new ContinuousImuStore(new File(root,"free"),8192,Long.MAX_VALUE);
        fails(()->lowFree.start(1,"导航","",false,WALL,0),IOException.class,"insufficient free space refuses session");
        ContinuousImuStore quota=new ContinuousImuStore(new File(root,"quota"),8192,0);
        quota.start(1,"导航","",false,WALL,0);
        boolean full=false;long cursor=0;
        for(int i=0;i<100 && !full;i++) {
            try { cursor=quota.append(batch(1,1,i*8L,8,i*8L+8,0,0,0,1),WALL+i,i); }
            catch(IOException expected) { full=true; }
        }
        check(full && cursor<800,"bounded quota stops instead of growing without limit");
        fails(()->quota.append(batch(1,1,800,8,808,0,0,0,1),WALL+100,100),IOException.class,"poisoned store refuses subsequent ACKs");
        check(new File(quota.snapshot().directory,"samples.csv").isFile(),"budget failure preserves history");
        journey(new File(root,"journey"));
        System.out.println("Continuous IMU store: "+checks+" filesystem checks passed");
    }

    private static void validTrace(File directory) throws IOException {
        try(java.util.stream.Stream<String> rows=Files.lines(new File(directory,"journey.csv").toPath())) {
            rows.filter(row->!row.startsWith("#")).forEach(row->{
                int comma=row.lastIndexOf(',');check(comma>0,"trace has checksum");
                check(line(row.substring(0,comma)).trim().equals(row),"trace row CRC matches");
            });
        }
    }
    private static void journey(File root) throws Exception {
        ContinuousImuStore s=new ContinuousImuStore(root,32L*1024*1024,0);
        s.start(100,"导航","原始证据",false,WALL,0);File original=s.snapshot().directory;
        ContinuousImuProtocol.Batch raw=batch(100,33,0,8,1000,3,7,2,1);
        check(s.append(raw,WALL+1,10)==8,"wire backlog next is independent of ACK cursor");
        s.append(raw,WALL+2,20);
        File trace=new File(original,"journey.csv");String data=Files.readString(trace.toPath());
        check(data.contains("H,100,33,0,1000,0,8,"),"trace preserves complete wire header, including backlog");
        check(data.lines().filter(v->v.startsWith("W,")).count()==16,"raw trace preserves retransmitted sample duplicates");
        check(s.snapshot().writtenSamples==8 && s.snapshot().duplicateSamples==8,"legacy journal remains deduplicated");
        s.traceGps(original,31.12,121.45,23.125,4.5,WALL-900,WALL,42,false,"amap_navi_gcj02");
        check(!Files.readString(trace.toPath()).contains("P,31.12"),"GPS buffers without per-fix fsync");
        s.flushTrace();
        check(Files.readString(trace.toPath()).contains("P,31.12,121.45,23.125,4.5,"+(WALL-900)+","+WALL+",42,900,-858,true,false,"),
                "GPS instantaneous speed and acquisition/receive times are distinct");
        s.traceEvent(original,"identity",0,Map.of("firmware","v7.12.9-nav.16-test","firmware_complete","true"),WALL,43,true);
        s.bindEventToken(900,original);
        s.traceEvent(original,"alert_started",900,Map.of("state","COUNTDOWN","name","SECRET_NAME","phone","SECRET_PHONE",
                "body","SECRET_SMS","prompt","SECRET_PROMPT"),WALL,44,true);
        s.stop("navigation_ended",WALL+10,50);
        s.start(101,"导航","第二段",false,WALL+20,60);File newer=s.snapshot().directory;
        s.traceEvent(s.eventDirectory(900),"sms_result",900,Map.of("part","1","result","sent"),WALL+30,70,true);
        check(s.snapshot().directory.equals(newer),"late callback never changes active IMU session");
        check(Files.readString(trace.toPath()).contains(Base64.getEncoder().encodeToString("sms_result".getBytes(StandardCharsets.UTF_8))),"late callback appends to original closed journey");
        String newerData=Files.readString(new File(newer,"journey.csv").toPath());
        check(!newerData.contains(",900,"),"late callback excluded from new journey");
        String plain=Files.readString(trace.toPath());
        for(String secret:new String[]{"SECRET_NAME","SECRET_PHONE","SECRET_SMS","SECRET_PROMPT"}) {
            check(!plain.contains(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8))),"sensitive field excluded: "+secret);
        }
        s.stop("completed",WALL+40,80);
        s.start(102,"导航","等待旧板尾部",false,WALL+41,81);File draining=s.snapshot().directory;
        File drill=s.diagnosticSession(103,WALL+42,82);
        s.traceEvent(drill,"alert_started",901,Map.of("source","manual_drill"),WALL+43,83,true);
        check(s.snapshot().directory.equals(draining) && s.snapshot().active,"independent diagnostic does not alter active drain state");
        check(s.append(batch(102,34,0,2,2,0,0,0,2),WALL+44,84)==2,"old board tail remains appendable after separate diagnostic");
        s.stop("navigation_ended",WALL+45,85);
        check("diagnostic_only".equals(properties(drill).getProperty("mode")) && "false".equals(properties(drill).getProperty("active")),"diagnostic session cannot claim active navigation");
        fails(()->s.bindEventToken(900,newer),IOException.class,"token cannot be silently rebound to a different journey");
        File outside=new File(root.getParentFile(),"outside");outside.mkdirs();Files.writeString(new File(outside,"metadata.properties").toPath(),"test");
        fails(()->s.traceEvent(outside,"test",0,Map.of(),WALL,1,true),IOException.class,"trace path cannot escape root");
        validTrace(original);validTrace(newer);

        // A valid H CRC with an incomplete W group must not masquerade as a replayable received batch.
        long good=trace.length();
        appendText(trace,line("H,100,33,8,1000,0,2,1000,1,3,7,2,0,0,"+WALL+",90"));
        appendText(trace,line("W,100,33,8,0,8,1000,0.0,0.0,9.8,-7.0,0.6,0.1,36.0"));
        appendText(trace,"W,100,33,8,1,9,torn");
        ContinuousImuStore restarted=new ContinuousImuStore(root,32L*1024*1024,0);
        check(restarted.recoverUnfinished().isEmpty(),"trace extension does not re-open completed schema1 sessions");
        check(trace.length()==good,"finished journey torn batch is truncated as one unit");
        check(restarted.eventDirectory(900).equals(original.getCanonicalFile()),"token binding survives process restart");
        restarted.traceEvent(restarted.eventDirectory(900),"sms_result",900,Map.of("delivery","delivered"),WALL+50,100,true);
        check(trace.length()>good,"post-restart late callback appends to same original journey");
        long crcGood=trace.length();appendText(trace,"E,invalid,0,1,2,00000000\n"+line("P,1,2,3,4,5,6,7,true,false,eA=="));
        new ContinuousImuStore(root,32L*1024*1024,0).recoverUnfinished();
        check(trace.length()==crcGood,"CRC-invalid trace suffix and later rows are removed");
        validTrace(original);
    }
    private static void seed(File root) throws Exception {
        for(String name:new String[]{"torn","crc","malformed","reboot"}) {
            ContinuousImuStore s=new ContinuousImuStore(new File(root,name),32L*1024*1024,0);
            s.start(555,"导航","异常退出恢复",false,WALL,10);
            s.append(batch(555,88,0,8,8,2,3,4,1),WALL+1,11);
            if(name.equals("reboot"))s.append(batch(555,89,0,2,2,1,2,3,1),WALL+2,12);
            // Intentionally no stop; JVM exit models process death with an active, synced journal.
        }
        System.out.println("Continuous IMU recovery: four process-death fixtures synced");
    }
    private static void recover(File root) throws Exception {
        for(String name:new String[]{"torn","crc","malformed","reboot"}) {
            File storeRoot=new File(root,name),directory=onlySession(storeRoot),journal=new File(directory,"samples.csv");
            metadata(directory,"durableNext","999999");metadata(directory,"writtenSamples","999999");
            if(name.equals("torn")) {
                appendText(journal,line("S,88,1,8,64,"+(WALL+2)+",12,0.1,-0.2,9.06,-7.0,0.6,0.1,35.2"));
                appendText(journal,"S,88,1,9,104,unfinished");
            } else if(name.equals("crc")) {
                appendText(journal,"D,100,00000000\n"+line("D,200"));
            } else if(name.equals("malformed")) {
                // A valid checksum cannot authorize a partially parsed record or later rows.
                appendText(journal,line("T,88,1,8,100,200,300,1,5,0,0,INVALID,12")+line("D,200"));
            }
            ContinuousImuStore s=new ContinuousImuStore(storeRoot,32L*1024*1024,0);
            List<ContinuousImuStore.Stats> list=s.recoverUnfinished();
            check(list.size()==1,"recover one interrupted "+name);
            ContinuousImuStore.Stats stats=list.get(0);
            long expected=name.equals("torn")?9:name.equals("reboot")?2:8;
            check(stats.durableNext==expected,"journal authoritative cursor "+name);
            check(stats.writtenSamples==(name.equals("torn")?9:name.equals("reboot")?10:8),"journal authoritative sample count "+name);
            check(stats.interrupted && stats.tailUnknown && !stats.active,"recovered session explicitly incomplete "+name);
            check(stats.overflow==(name.equals("reboot")?3:2) && stats.duplicateSamples==0,"invalid suffix cannot mutate state "+name);
            check(!Files.readString(journal.toPath()).contains("unfinished"),"torn suffix removed "+name);
            check(s.recoverUnfinished().isEmpty(),"recovery idempotent "+name);
        }
        System.out.println("Continuous IMU recovery: "+checks+" crash/corruption checks passed");
    }
    private static void longRun(File root) throws Exception {
        final int total=90000;
        ContinuousImuStore s=new ContinuousImuStore(root,32L*1024*1024,0);
        s.start(999,"导航","synthetic one hour at 25 Hz",false,WALL,0);
        long begin=System.nanoTime();
        File directory=s.snapshot().directory;
        long nextGps=0;int gpsCount=0;
        for(int first=0;first<total;first+=8) {
            int count=Math.min(8,total-first);
            while(nextGps<=first*40L) {
                s.traceGps(directory,31.1+gpsCount*0.000001,121.4,Math.max(0,40.5-gpsCount%30),4.2,
                        WALL+nextGps-100,WALL+nextGps,nextGps,false,"synthetic_gcj02");
                if(gpsCount%60==0) s.traceEvent(directory,"detector_state",0,Map.of("state","READY"),WALL+nextGps,nextGps,false);
                nextGps+=1000;gpsCount++;
            }
            long ack=s.append(batch(999,33,first,count,first+count,0,0,0,1),WALL+first*40L,first*40L);
            check(ack==first+count,"long-run durable cursor "+first);
        }
        s.stop("navigation_ended",WALL+3600000,3600000);
        ContinuousImuStore.Stats stats=s.snapshot();
        check(stats.writtenSamples==total && stats.gapSamples==0 && stats.durableNext==total,"one-hour no synthetic losses");
        long rows;
        try(java.util.stream.Stream<String> lines=Files.lines(new File(stats.directory,"samples.csv").toPath())) { rows=lines.filter(v->v.startsWith("S,")).count(); }
        check(rows==total,"streaming disk sample count");
        long rawRows,gpsRows,headers;
        try(java.util.stream.Stream<String> lines=Files.lines(new File(directory,"journey.csv").toPath())) { rawRows=lines.filter(v->v.startsWith("W,")).count(); }
        try(java.util.stream.Stream<String> lines=Files.lines(new File(directory,"journey.csv").toPath())) { gpsRows=lines.filter(v->v.startsWith("P,")).count(); }
        try(java.util.stream.Stream<String> lines=Files.lines(new File(directory,"journey.csv").toPath())) { headers=lines.filter(v->v.startsWith("H,")).count(); }
        check(rawRows==total && gpsRows==3600 && headers==11250,"one-hour all raw batch headers, samples and 1Hz GPS persisted");
        validTrace(directory);
        long bytes;
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(root.toPath())) { bytes=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).sum(); }
        check(bytes<32L*1024*1024,"one-hour unified package stays below shared 32MiB budget");
        System.out.println("Continuous IMU long run: "+total+" synthetic samples + "+gpsRows+" GPS under 32 MiB heap in "+((System.nanoTime()-begin)/1000000)+" ms; samples="+new File(stats.directory,"samples.csv").length()+" bytes; journey="+new File(directory,"journey.csv").length()+" bytes; total="+bytes);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("root mode required");
        File root=new File(args[0]);if(!root.isDirectory() && !root.mkdirs())throw new IOException("fixture root");
        switch(args[1]) {
            case "basic":basic(root);break;
            case "seed":seed(root);break;
            case "recover":recover(root);break;
            case "long":longRun(root);break;
            default:throw new IllegalArgumentException("unknown test mode");
        }
    }
}
