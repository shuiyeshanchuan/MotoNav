package com.example.motonav;

import android.app.Application;
import android.app.PendingIntent;
import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.CRC32;
import org.json.JSONObject;

/** JVM substitutes exercise the compiled Android controller/SMS sender without a modem or network. */
public final class EmergencyIntegrationTest {
    private static int checks;
    private static void check(boolean pass,String text){checks++;if(!pass)throw new AssertionError(text);}
    private static Application app;
    private static File directory;
    private static EmergencyAlertController c(){return EmergencyAlertController.INSTANCE;}
    private static String save(int seconds,boolean realSms) {
        return c().save(new EmergencyConfig("测试骑手","13800138000","测试提示",seconds,7,realSms));
    }
    private static void cleanEvent() { if(c().getStateName().equals("COUNTDOWN")) c().cancel(c().getEventToken()); }
    private static void cancelFlow() {
        check(save(30,true)==null,"save real SMS configuration");
        check(c().startDrill().contains("演练已启动"),"start drill");long token=c().getEventToken();
        check(c().getStateName().equals("COUNTDOWN"),"countdown state");
        check(c().cancel(token).contains("已取消"),"phone cancellation");
        Handler.advance(30_000);
        check(SmsManager.submissions.isEmpty(),"cancelled countdown never submits SMS");
        c().onSmsResult(token,0,1,false,Activity.RESULT_OK);check(c().getStateName().equals("CANCELLED"),"late callback cannot revive cancellation");
    }
    private static void dryRun() {
        check(save(0,false)==null,"save dry run");
        check(c().startDrill().contains("不发短信"),"zero-second dry run starts");
        Handler.advance(0);
        check(c().getStateName().equals("DRY_RUN"),"zero-second expiration is dry run");
        check(SmsManager.submissions.isEmpty(),"realSms false cannot submit");
    }
    private static void realSms() {
        check(save(0,true)==null,"save real zero-second");
        check(c().startDrill().contains("真实发送"),"zero-second real drill starts");
        Handler.advance(0);
        check(SmsManager.submissions.size()==1,"one modem submission");
        String sendingStatus=c().getStatus(),sendingReport=c().latestReport();String disarmed=c().disarm();
        check(disarmed.contains("不能撤回")&&c().getStateName().equals("SENDING")&&c().getStatus().equals(sendingStatus)&&c().latestReport().equals(sendingReport),"disarm cannot imply a submitted modem action was recalled");
        SmsManager.Submission submission=SmsManager.submissions.get(0);check(submission.subscriptionId==7 && submission.phone.equals("13800138000"),"selected SIM and phone forwarded");
        long token=c().getEventToken();int total=submission.parts.size();
        // A failed first callback is terminal for that part; duplicate SUCCESS cannot erase it.
        submission.sent.get(0).dispatch(1);check(c().getStateName().equals("FAILED"),"send failure is visible");
        submission.sent.get(0).dispatch(Activity.RESULT_OK);check(c().getStateName().equals("FAILED"),"duplicate callback cannot wash out failure");
        submission.delivered.get(0).dispatch(Activity.RESULT_OK);check(c().getStateName().equals("FAILED"),"delivery callback cannot revive failed send");
        check(total>=1,"multipart has at least one segment");
    }
    private static void permissionAndSim() {
        SmsManager.submissions.clear();check(save(0,true)==null,"save permission test");
        app.permissions.put(android.Manifest.permission.SEND_SMS,-1);
        check(c().startDrill().contains("短信"),"SEND_SMS denial blocks drill");check(SmsManager.submissions.isEmpty(),"permission denial no SMS");
        app.permissions.put(android.Manifest.permission.SEND_SMS,0);check(save(0,true)==null,"save unknown sim test");
        app.subscriptions.active.clear();check(c().startDrill().contains("SIM"),"missing selected SIM blocks drill");check(SmsManager.submissions.isEmpty(),"missing SIM no SMS");
        app.subscriptions.active.add(new android.telephony.SubscriptionInfo(7));app.subscriptions.active.add(new android.telephony.SubscriptionInfo(9));
    }
    private static void navAndBoardCancel() {
        check(save(30,false)==null,"save nav cancellation");c().navigationStarted("导航",false);check(c().arm().contains("急刹测试"),"arm during real navigation");
        check(c().startDrill().contains("演练已启动"),"start independent drill");long token=c().getEventToken();
        c().navigationStopped();check(c().getStateName().equals("COUNTDOWN"),"stopping nav is not explicit alert cancellation");
        c().onBoardCancel(token);check(c().getStateName().equals("CANCELLED"),"board cancellation uses matching token");
        c().onBoardCancel(token+1);check(c().getStateName().equals("CANCELLED"),"wrong board token ignored");
    }
    private static void duplicateResults() {
        SmsManager.submissions.clear();check(save(0,true)==null,"save callback test");check(c().startDrill().contains("真实发送"),"start callback test");Handler.advance(0);SmsManager.Submission s=SmsManager.submissions.get(0);long token=c().getEventToken();int total=s.parts.size();
        for(int i=0;i<total;i++)s.sent.get(i).dispatch(Activity.RESULT_OK);
        check(c().getStateName().equals("SUBMITTED"),"all sent callbacks submitted");
        String submittedStatus=c().getStatus(),submittedReport=c().latestReport();String disarmed=c().disarm();
        check(disarmed.contains("不能撤回")&&c().getStatus().equals(submittedStatus)&&c().latestReport().equals(submittedReport),"disarm cannot replace submitted SMS result with disabled status");
        s.sent.get(0).dispatch(1);check(c().getStateName().equals("SUBMITTED"),"duplicate callback ignored after success");
        for(int i=0;i<total;i++)s.delivered.get(i).dispatch(Activity.RESULT_OK,new byte[]{0},"3gpp");
        check(c().getStateName().equals("DELIVERED"),"all delivery callbacks delivered");
        s.delivered.get(0).dispatch(1);check(c().getStateName().equals("DELIVERED"),"duplicate delivery ignored after success");
        c().onSmsResult(token,0,total,false,Activity.RESULT_OK);
    }
    private static JSONObject report(long token) {
        try{return new JSONObject(Files.readString(new File(directory,"event-"+String.format(Locale.US,"%08X",token)+".json").toPath()));}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static SmsManager.Submission submit(int seconds,int sim) {
        check(c().save(new EmergencyConfig("测试骑手","13800138000","按住取消测试",seconds,sim,true))==null,"save submission config");
        SmsManager.submissions.clear();
        SmsManager.beforeSubmit=()->check(report(c().getEventToken()).getString("phase").equals("SEND_CLAIMED"),"send claim durable before modem API");
        check(c().startDrill().contains("演练已启动"),"start submission");Handler.advance(seconds*1000L);
        SmsManager.beforeSubmit=null;
        check(SmsManager.submissions.size()==1,"exactly one multipart API invocation");return SmsManager.submissions.get(0);
    }
    private static void countdownsAndHistory() {
        SmsManager.submissions.clear();check(save(0,true)==null,"zero cancel config");c().startDrill();long token=c().getEventToken();c().cancel(token);Handler.advance(0);
        check(c().getStateName().equals("CANCELLED")&&SmsManager.submissions.isEmpty(),"cancel before zero-second posted expiry wins");
        check(save(30,true)==null,"30s countdown config");c().startDrill();token=c().getEventToken();
        check(c().getRemainingSeconds()==30 && app.notifications.latest.actions.size()==1,"phone countdown has cancel action");
        check(c().save(new EmergencyConfig("新姓名","13800138000","x",30,7,true))!=null,"busy countdown config immutable");
        c().navigationStopped();Handler.advance(29999);check(SmsManager.submissions.isEmpty()&&c().getStateName().equals("COUNTDOWN"),"30s never sends early and nav stop does not cancel");
        Handler.advance(1);check(SmsManager.submissions.size()==1,"30s expires once");SmsManager.Submission old=SmsManager.submissions.get(0);long oldToken=token;
        Handler.advance(60100);check(c().getStateName().equals("UNKNOWN"),"missing modem callbacks become UNKNOWN");Handler.advance(120000);check(SmsManager.submissions.size()==1,"UNKNOWN never automatically retries");
        check(save(60,false)==null,"60s cancel config");c().startDrill();token=c().getEventToken();check(token!=oldToken,"unique event identity");
        Handler.advance(59999);app.notifications.latest.actions.get(0).dispatch(Activity.RESULT_OK);Handler.advance(1);
        check(c().getStateName().equals("CANCELLED")&&SmsManager.submissions.size()==1,"notification cancellation at 60s deadline prevents another SMS");
        for(PendingIntent pending:old.sent)pending.dispatch(Activity.RESULT_OK);
        check(report(oldToken).getString("state").equals("SUBMITTED"),"late sent callbacks update original event file");
        check(c().getStateName().equals("CANCELLED")&&c().getEventToken()==token,"old callbacks cannot alter current event");
        for(PendingIntent pending:old.delivered)pending.dispatch(Activity.RESULT_OK,new byte[]{0},"3gpp");
        check(report(oldToken).getString("state").equals("DELIVERED"),"late delivery reports remain with original token");
        SmsManager.Submission sixty=submit(60,9);check(sixty.subscriptionId==9,"explicit second SIM on dual-SIM device");
        for(PendingIntent pending:sixty.sent)pending.dispatch(Activity.RESULT_OK);
        check(c().getStateName().equals("SUBMITTED"),"60s real countdown submission succeeds");
    }
    private static void deliveryPdu() {
        SmsManager.Submission s=submit(0,7);int total=s.parts.size();
        for(PendingIntent pending:s.sent)pending.dispatch(Activity.RESULT_OK);
        check((s.sent.get(0).flags&PendingIntent.FLAG_IMMUTABLE)!=0 && (s.delivered.get(0).flags&PendingIntent.FLAG_MUTABLE)!=0,"sent immutable and delivery explicit mutable");
        for(PendingIntent pending:s.delivered)pending.dispatch(Activity.RESULT_OK);
        check(c().getStateName().equals("SUBMITTED"),"RESULT_OK without PDU is not delivered");
        for(PendingIntent pending:s.delivered)pending.dispatch(Activity.RESULT_OK,new byte[]{32},"3gpp");
        check(c().getStateName().equals("SUBMITTED"),"temporary delivery status is not success");
        for(int pduStatus:new int[]{1,2,16,31,128,255}) {
            for(PendingIntent pending:s.delivered)pending.dispatch(Activity.RESULT_OK,new byte[]{(byte)pduStatus},"3gpp");
            check(c().getStateName().equals("SUBMITTED"),"non-confirming/reserved GSM status is never delivery success or confirmed failure: "+pduStatus);
        }
        for(PendingIntent pending:s.delivered)pending.dispatch(Activity.RESULT_OK,new byte[]{0},"3gpp2");
        check(c().getStateName().equals("SUBMITTED"),"CDMA report is unknown rather than assumed GSM success");
        s.delivered.get(0).dispatch(Activity.RESULT_OK,new byte[]{64},"3gpp");
        check(c().getStateName().equals("FAILED"),"permanent PDU delivery failure visible");
        String failedStatus=c().getStatus(),failedReport=c().latestReport();c().disarm();
        check(c().getStateName().equals("FAILED")&&c().getStatus().equals(failedStatus)&&c().latestReport().equals(failedReport),"disarm preserves failure evidence and displayed result");
        for(PendingIntent pending:s.delivered)pending.dispatch(Activity.RESULT_OK,new byte[]{0},"3gpp");
        check(c().getStateName().equals("FAILED"),"later duplicate success cannot erase delivery failure");
        s=submit(0,7);for(PendingIntent pending:s.sent)pending.dispatch(Activity.RESULT_OK);
        for(PendingIntent pending:s.delivered)pending.dispatch(Activity.RESULT_OK,new byte[]{0},"3gpp");
        check(c().getStateName().equals("DELIVERED"),"all valid GSM status reports confirm delivery");
        c().onSmsResult(c().getEventToken(),0,total+1,false,1);check(c().getStateName().equals("DELIVERED"),"wrong multipart total rejected");
        Intent invalid=new Intent(app,EmergencySmsReceiver.class).setAction("com.example.motonav.EMERGENCY_SENT").setData(Uri.parse("other://sms/1/0/0")).putExtra("total",1);
        EmergencySmsReceiver receiver=new EmergencySmsReceiver();receiver.setResultCode(1);receiver.onReceive(app,invalid);check(c().getStateName().equals("DELIVERED"),"invalid callback URI ignored");
    }
    private static void failures() {
        check(save(0,true)==null,"save failure config");app.permissions.put(android.Manifest.permission.READ_PHONE_STATE,-1);
        check(c().startDrill().contains("权限"),"READ_PHONE_STATE denial blocks SMS");app.permissions.put(android.Manifest.permission.READ_PHONE_STATE,0);
        app.packages.messaging=false;check(c().startDrill().contains("不支持"),"non-SMS hardware rejected");app.packages.messaging=true;
        app.notifications.enabled=false;check(c().startDrill().contains("通知"),"disabled notification blocks countdown");app.notifications.enabled=true;
        app.notifications.getNotificationChannel("motonav_brake_test_v1").setImportance(0);check(c().startDrill().contains("通知"),"blocked alarm channel rejected");app.notifications.getNotificationChannel("motonav_brake_test_v1").setImportance(4);
        check(c().save(new EmergencyConfig("测试骑手","13800138000","x",0,-1,true))==null,"SIM selection stored for validation");check(c().startDrill().contains("SIM"),"no default-SIM guessing");
        check(save(0,false)==null,"dry run without permission config");app.permissions.put(android.Manifest.permission.SEND_SMS,-1);int before=SmsManager.submissions.size();c().startDrill();Handler.advance(0);
        check(c().getStateName().equals("DRY_RUN")&&SmsManager.submissions.size()==before,"dry run stays available without SMS permission and cannot send");app.permissions.put(android.Manifest.permission.SEND_SMS,0);
        check(save(0,true)==null,"modem failure config");SmsManager.throwOnSend=true;c().startDrill();Handler.advance(0);SmsManager.throwOnSend=false;
        check(c().getStateName().equals("FAILED"),"modem exception visible");Handler.advance(120000);check(SmsManager.submissions.size()==before,"failed modem API not retried");
        check(save(0,true)==null,"multipart overflow config");SmsManager.segmentLength=1;c().startDrill();Handler.advance(0);SmsManager.segmentLength=70;
        check(c().getStateName().equals("FAILED")&&SmsManager.submissions.size()==before,"excessive multipart count rejected without sending");
        check(save(30,true)==null,"start persistence failure config");AtomicFile.failName="latest.json";c().startDrill();AtomicFile.failName="";Handler.advance(31000);
        check(c().getStateName().equals("INTERRUPTED")&&SmsManager.submissions.size()==before,"initial persistence failure cannot leave latent SMS timer");
        check(save(1,true)==null,"claim persistence failure config");c().startDrill();AtomicFile.failName="latest.json";Handler.advance(1000);AtomicFile.failName="";
        check(!c().getStateName().equals("SENDING")&&SmsManager.submissions.size()==before,"failed durable claim prevents SMS API");
        check(save(30,true)==null,"cancellation persistence failure config");c().startDrill();AtomicFile.failName="latest.json";c().cancel(c().getEventToken());AtomicFile.failName="";Handler.advance(30000);
        check(c().getStateName().equals("CANCELLED")&&SmsManager.submissions.size()==before,"storage failure cannot undo in-memory cancellation");
        check(save(0,false)==null,"zero dry expiry storage-failure config");c().startDrill();AtomicFile.failName="latest.json";
        Handler.advance(0);AtomicFile.failName="";
        check(c().getStateName().equals("INTERRUPTED")&&SmsManager.submissions.size()==before,"zero-second dry persistence failure cannot escape Handler callback or send");
        SmsManager.Submission prior=submit(0,7);for(PendingIntent pending:prior.sent)pending.dispatch(Activity.RESULT_OK);
        long priorToken=c().getEventToken();String priorReport=report(priorToken).toString();
        check(save(30,false)==null,"new drill after submitted config");AtomicFile.failName="config.json";
        String error=c().startDrill();AtomicFile.failName="";
        check(error.contains("原事件结果已保留")&&c().getEventToken()==priorToken&&c().getStateName().equals("SUBMITTED"),"pre-allocation write failure preserves submitted event state");
        check(report(priorToken).toString().equals(priorReport),"failed new drill does not rewrite prior persisted report");
        NavService.failStarts=true;error=c().startDrill();NavService.failStarts=false;
        check(error.contains("原事件结果已保留")&&c().getStateName().equals("SUBMITTED"),"foreground launch failure cannot rewrite prior submission");
    }
    private static long sequence;
    private static void raw(double dynamic,boolean backlog) {
        long board=SystemClock.now&0xffffffffL;
        ByteBuffer b=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(123).putInt(77).putInt((int)sequence).putInt((int)(sequence+1+(backlog?100:0))).putInt((int)sequence);
        b.putInt(0).putInt(0).putInt(0).putInt((int)board).putInt(0).putInt(0).putShort((short)1).putShort((short)1);
        b.putInt((int)board).putFloat((float)-dynamic).putFloat(0).putFloat(9.068f).putFloat(-7).putFloat(0.6f).putFloat(0.1f).putFloat(36);
        CRC32 crc=new CRC32();crc.update(b.array(),0,80);b.putInt((int)crc.getValue());sequence++;
        ContinuousImuRecorder.INSTANCE.observer.invoke(ContinuousImuProtocol.parseBody(b.array()),SystemClock.now);
        Handler.advance(40);
    }
    private static void gps(double speed) {long wall=System.currentTimeMillis();c().onFix(31.2,121.5,wall,wall,5,speed);}
    private static void sensorToCountdown() {
        check(save(30,false)==null,"sensor trigger config");c().navigationStarted("导航",true);c().arm();long oldToken=c().getEventToken();
        for(int i=0;i<100;i++)raw(0,false);gps(36);for(int i=0;i<8;i++)raw(4,false);gps(0);
        check(c().getEventToken()==oldToken,"simulation cannot generate an event");c().disarm();
        c().navigationStarted("导航",false);c().arm();sequence=0;
        for(int i=0;i<100;i++)raw(0,false);gps(36);
        for(int i=0;i<8;i++)raw(4,true);gps(0);
        check(c().getEventToken()==oldToken,"historical backlog cannot generate an event");
        for(int i=0;i<100;i++)raw(0,false);gps(36);for(int i=0;i<8;i++)raw(4,false);gps(20);
        check(c().getEventToken()!=oldToken&&c().getStateName().equals("COUNTDOWN"),"actual controller raw IMU plus real GPS reaches countdown");
        check(report(c().getEventToken()).getString("source").equals("imu_gps_brake"),"sensor trigger distinguished from manual drill");
        c().onBoardCancel(c().getEventToken());check(c().getStateName().equals("CANCELLED")&&!c().getArmed(),"sensor event single-use and board-cancellable");
    }
    private static void promptCache() {
        String longPrompt="请确认您目前平安无事，持续按住按钮半秒即可取消此次报警提醒，本条提示用于验证两段文字在短提示替换后能完整清除";
        check(c().save(new EmergencyConfig("测试骑手","13800138000",longPrompt,30,7,false))==null,"long two-slice prompt config");
        c().startDrill();check(BleLink.retainedImages.get(15).w>0&&BleLink.visibleImages.containsKey(15),"long prompt populates second retained slot");c().cancel(c().getEventToken());
        check(c().save(new EmergencyConfig("测试骑手","13800138000","短提示",30,7,false))==null,"short one-slice prompt config");
        c().startDrill();check(BleLink.retainedImages.get(15).w==0&&BleLink.retainedImages.get(15).bytes.length==0,"short prompt explicitly replaces old retained slot with clear");
        check(!BleLink.visibleImages.containsKey(15),"old second slice erased on connected screen");
        BleLink.connected=false;Handler.advance(100);BleLink.visibleImages.clear();BleLink.connected=true;BleLink.resendRetainedImages();Handler.advance(100);
        check(!BleLink.visibleImages.containsKey(15)&&BleLink.retainedImages.get(15).w==0,"reconnect retained replay cannot restore old second slice");
        check(new String(BleLink.visibleImages.get(14).bytes,java.nio.charset.StandardCharsets.UTF_8).equals("短提示"),"reconnect still displays current first slice");c().cancel(c().getEventToken());
    }
    private static void seed(String mode) {
        check(save(mode.equals("countdown")?30:0,true)==null,"persist restart config");c().startDrill();
        if(!mode.equals("countdown"))Handler.advance(0);
        if(mode.equals("submitted"))for(PendingIntent p:SmsManager.submissions.get(0).sent)p.dispatch(Activity.RESULT_OK);
        check(c().getStateName().equals(mode.equals("countdown")?"COUNTDOWN":mode.equals("claimed")?"SENDING":"SUBMITTED"),"seed durable restart state "+mode);
    }
    private static void restarted(String mode) {
        check(c().getStateName().equals(mode.equals("submitted")?"SUBMITTED":"INTERRUPTED"),"restart preserves terminal or interrupts active state "+mode);
        Handler.advance(180000);check(SmsManager.submissions.isEmpty(),"process restart cannot submit or retry historical SMS "+mode);
    }
    public static void main(String[] args) throws Exception {
        String mode=args.length>1?args[1]:"normal";
        String sub=mode.startsWith("seed-")?mode.substring(5):mode.startsWith("restart-")?mode.substring(8):mode;
        File root=new File(args.length==0?"android/.local/emergency-integration":args[0],sub);if(!root.isDirectory()&&!root.mkdirs())throw new AssertionError("fixture root");
        directory=new File(root,"no-backup/emergency_test");
        if(mode.equals("blocked-init")){Files.createDirectories(directory.getParentFile().toPath());Files.writeString(directory.toPath(),"regular file blocks directory");}
        if(mode.equals("corrupt-init")){Files.createDirectories(directory.toPath());Files.writeString(new File(directory,"config.json").toPath(),"malformed JSON");}
        app=new Application(root);SystemClock.now=mode.startsWith("restart-")?50:100000;c().init(app);
        if(mode.startsWith("seed-"))seed(sub);
        else if(mode.startsWith("restart-"))restarted(sub);
        else if(mode.equals("blocked-init")||mode.equals("corrupt-init")) {
            check(c().getStatus().contains("存储")||c().getStatus().contains("读取失败"),"initialization failure exposed without crashing application");
            check(save(0,true)!=null&&c().readiness(true)!=null,"failed storage blocks save and sending readiness");
            check(!c().startDrill().contains("演练已启动"),"failed storage cannot start an alert");Handler.advance(120000);check(SmsManager.submissions.isEmpty(),"failed storage no background SMS");
        } else {cancelFlow();dryRun();realSms();permissionAndSim();navAndBoardCancel();duplicateResults();countdownsAndHistory();deliveryPdu();failures();sensorToCountdown();promptCache();}
        System.out.println("Emergency Android integration: "+checks+" controller/SMS substitute checks passed; no modem/network used");
    }
}
