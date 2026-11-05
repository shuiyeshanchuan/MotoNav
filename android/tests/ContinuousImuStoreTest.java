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
        System.out.println("Continuous IMU store: "+checks+" filesystem checks passed");
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
        for(int first=0;first<total;first+=8) {
            int count=Math.min(8,total-first);
            long ack=s.append(batch(999,33,first,count,first+count,0,0,0,1),WALL+first*40L,first*40L);
            check(ack==first+count,"long-run durable cursor "+first);
        }
        s.stop("navigation_ended",WALL+3600000,3600000);
        ContinuousImuStore.Stats stats=s.snapshot();
        check(stats.writtenSamples==total && stats.gapSamples==0 && stats.durableNext==total,"one-hour no synthetic losses");
        long rows;
        try(java.util.stream.Stream<String> lines=Files.lines(new File(stats.directory,"samples.csv").toPath())) { rows=lines.filter(v->v.startsWith("S,")).count(); }
        check(rows==total,"streaming disk sample count");
        System.out.println("Continuous IMU long run: "+total+" synthetic samples persisted under 32 MiB heap in "+((System.nanoTime()-begin)/1000000)+" ms; journal="+new File(stats.directory,"samples.csv").length()+" bytes");
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
