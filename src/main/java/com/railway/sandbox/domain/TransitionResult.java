package com.railway.sandbox.domain;

import com.railway.sandbox.model.RuntimeState;

import java.util.ArrayList;
import java.util.List;

/** Result of firing one operation: accepted transition plus any invariants broken. */
public final class TransitionResult {
    public boolean accepted;
    public String rejectReason;
    public RuntimeState state;
    /** Invariants violated after the transition. */
    public List<String> violations = new ArrayList<>();
    /** Human-readable trigger condition description. */
    public String trigger;
    public String effect;

    static TransitionResult rejected(RuntimeState state, String reason, String trigger) {
        TransitionResult r = new TransitionResult();
        r.accepted = false;
        r.rejectReason = reason;
        r.state = state;
        r.trigger = trigger;
        r.effect = "操作被拒绝，状态不变";
        return r;
    }
}
