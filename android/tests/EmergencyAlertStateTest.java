package com.example.motonav;

public final class EmergencyAlertStateTest {
    private static int checks;
    private static void check(boolean pass,String text) { checks++;if(!pass)throw new AssertionError(text); }
    private static void invalid(Runnable action,String text) {
        try { action.run();throw new AssertionError(text); }catch(IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        check(EmergencyAlertState.DEFAULT_COUNTDOWN_SECONDS==30,"default 30 seconds");
        for(int seconds=0;seconds<=60;seconds++) {
            EmergencyAlertState state=EmergencyAlertState.start("event-"+seconds,1000,seconds);
            check(state.deadlineMonotonicMs==1000+seconds*1000L,"monotonic deadline");
            check(!state.isExpired(999),"clock rollback cannot expire");
            check(state.isExpired(state.deadlineMonotonicMs),"exact deadline expired including zero");
            check(state.claimSend(state.token,state.deadlineMonotonicMs,true).phase==EmergencyAlertState.Phase.CANCELLED,"cancellation wins simultaneous expiry");
            EmergencyAlertState cancelled=state.cancel(state.token);
            check(cancelled.claimSend(state.token,state.deadlineMonotonicMs,false)==cancelled,"cancelled never sendable");
            EmergencyAlertState claimed=state.claimSend(state.token,state.deadlineMonotonicMs,false);
            check(claimed.phase==EmergencyAlertState.Phase.SEND_CLAIMED,"one claim when expired");
            check(claimed.claimSend(state.token,state.deadlineMonotonicMs+1,false)==claimed,"duplicate callback cannot claim again");
            check(claimed.sent(state.token).phase==EmergencyAlertState.Phase.SENT,"submitted event marked sent");
            check(claimed.failed(state.token).phase==EmergencyAlertState.Phase.FAILED,"failure terminal without retry");
            check(claimed.failed(state.token).claimSend(state.token,999999,false).phase==EmergencyAlertState.Phase.FAILED,"no implicit failure retry");
            check(state.restoreInterrupted().phase==EmergencyAlertState.Phase.PROCESS_INTERRUPTED,"restart stops countdown");
            check(claimed.restoreInterrupted().phase==EmergencyAlertState.Phase.PROCESS_INTERRUPTED,"restart cannot resend claimed SMS");
            check(EmergencyAlertState.restore(state.token,state.startMonotonicMs,state.deadlineMonotonicMs,state.countdownSeconds,state.phase).phase==EmergencyAlertState.Phase.PROCESS_INTERRUPTED,"persisted countdown restores interrupted");
        }
        EmergencyAlertState s=EmergencyAlertState.start("one",1000,30);
        check(s.remainingMs(11000)==20000 && s.remainingMs(999)==30000,"remaining duration uses monotonic epoch");
        check(s.claimSend("one",30999,false)==s,"not before deadline");
        check(s.cancel("other")==s && s.claimSend("other",31000,false)==s,"old token callbacks inert");
        check(s.sent("one")==s && s.failed("one")==s,"no sent/failed without claim");
        EmergencyAlertState claimed=s.claimSend("one",31000,false);
        check(claimed.cancel("one")==claimed,"cannot recall already claimed send");
        check(claimed.sent("other")==claimed && claimed.failed("other")==claimed,"callbacks require matching token");
        check(s.claimSend("one",999,false).phase==EmergencyAlertState.Phase.PROCESS_INTERRUPTED,"backward clock blocks send");
        EmergencyAlertState sent=claimed.sent("one");
        check(sent.restoreInterrupted()==sent && EmergencyAlertState.restore("one",1000,31000,30,EmergencyAlertState.Phase.SENT).phase==EmergencyAlertState.Phase.SENT,"terminal state preserved across recovery");
        invalid(()->EmergencyAlertState.start("",0,30),"token required");
        invalid(()->EmergencyAlertState.start("x",-1,30),"negative time rejected");
        invalid(()->EmergencyAlertState.start("x",0,-1),"negative duration rejected");
        invalid(()->EmergencyAlertState.start("x",0,61),"duration over 60 rejected");
        invalid(()->EmergencyAlertState.start("x",Long.MAX_VALUE,60),"deadline overflow rejected");
        invalid(()->EmergencyAlertState.restore("x",1000,1234,30,EmergencyAlertState.Phase.COUNTDOWN),"inconsistent persistence rejected");
        System.out.println("Emergency TEST alert state: "+checks+" lifecycle checks passed");
    }
}
