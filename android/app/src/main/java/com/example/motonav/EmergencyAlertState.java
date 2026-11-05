package com.example.motonav;

/**
 * Immutable local TEST-alert lifecycle. No Android/SMS calls. The owner must serialize
 * cancellation and send-claim, durably save SEND_CLAIMED before invoking SMS once,
 * and never resend a claimed event after process restart. Claim is the irreversible
 * send boundary: cancellation received before/simultaneously with claim wins;
 * cancellation after SMS submission cannot recall a message.
 */
public final class EmergencyAlertState {
    public static final int DEFAULT_COUNTDOWN_SECONDS=30;
    public enum Phase { COUNTDOWN, CANCELLED, SEND_CLAIMED, SENT, FAILED, PROCESS_INTERRUPTED }
    public final String token;
    public final long startMonotonicMs, deadlineMonotonicMs;
    public final int countdownSeconds;
    public final Phase phase;
    private EmergencyAlertState(String token,long start,long deadline,int seconds,Phase phase) {
        this.token=token;startMonotonicMs=start;deadlineMonotonicMs=deadline;countdownSeconds=seconds;this.phase=phase;
    }
    public static EmergencyAlertState start(String token,long nowMs,int countdownSeconds) {
        if(token==null || token.isEmpty() || token.length()>128 || nowMs<0 || countdownSeconds<0 || countdownSeconds>60 ||
                nowMs>Long.MAX_VALUE-countdownSeconds*1000L)throw new IllegalArgumentException("invalid alert countdown");
        return new EmergencyAlertState(token,nowMs,nowMs+countdownSeconds*1000L,countdownSeconds,Phase.COUNTDOWN);
    }
    /** Persisted active/claimed work never becomes sendable on process recreation. */
    public static EmergencyAlertState restore(String token,long startMs,long deadlineMs,int seconds,Phase savedPhase) {
        EmergencyAlertState initial=start(token,startMs,seconds);
        if(initial.deadlineMonotonicMs!=deadlineMs || savedPhase==null)throw new IllegalArgumentException("invalid persisted alert");
        return initial.with(savedPhase==Phase.COUNTDOWN || savedPhase==Phase.SEND_CLAIMED?Phase.PROCESS_INTERRUPTED:savedPhase);
    }
    public boolean isExpired(long nowMs) { return phase==Phase.COUNTDOWN && nowMs>=startMonotonicMs && nowMs>=deadlineMonotonicMs; }
    public long remainingMs(long nowMs) {
        if(phase!=Phase.COUNTDOWN)return 0;
        if(nowMs<startMonotonicMs)return deadlineMonotonicMs-startMonotonicMs;
        return Math.max(0,deadlineMonotonicMs-nowMs);
    }
    public EmergencyAlertState cancel(String expectedToken) {
        return matches(expectedToken) && phase==Phase.COUNTDOWN?with(Phase.CANCELLED):this;
    }
    /** cancelRequested is checked first, including for zero-second countdowns. */
    public EmergencyAlertState claimSend(String expectedToken,long nowMs,boolean cancelRequested) {
        if(!matches(expectedToken) || phase!=Phase.COUNTDOWN)return this;
        if(cancelRequested)return with(Phase.CANCELLED);
        if(nowMs<startMonotonicMs)return with(Phase.PROCESS_INTERRUPTED);
        return isExpired(nowMs)?with(Phase.SEND_CLAIMED):this;
    }
    public EmergencyAlertState sent(String expectedToken) { return matches(expectedToken) && phase==Phase.SEND_CLAIMED?with(Phase.SENT):this; }
    public EmergencyAlertState failed(String expectedToken) { return matches(expectedToken) && phase==Phase.SEND_CLAIMED?with(Phase.FAILED):this; }
    public EmergencyAlertState restoreInterrupted() {
        return phase==Phase.COUNTDOWN || phase==Phase.SEND_CLAIMED?with(Phase.PROCESS_INTERRUPTED):this;
    }
    private boolean matches(String expectedToken) { return token.equals(expectedToken); }
    private EmergencyAlertState with(Phase next) { return new EmergencyAlertState(token,startMonotonicMs,deadlineMonotonicMs,countdownSeconds,next); }
}
