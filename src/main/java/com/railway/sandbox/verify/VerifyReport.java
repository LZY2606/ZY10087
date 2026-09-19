package com.railway.sandbox.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Full structured result of one verification run. */
public final class VerifyReport {
    /** SAFE: every reachable state honours the invariants. */
    public static final String SAFE = "SAFE";
    /** LIMIT_REACHED: search stopped at the explicit bound; not a proof. */
    public static final String LIMIT_REACHED = "LIMIT_REACHED";
    /** COUNTEREXAMPLE: shortest interleaving into a violating state found. */
    public static final String COUNTEREXAMPLE = "COUNTEREXAMPLE";
    /** TOPOLOGY_INVALID: verification refused because the topology has issues. */
    public static final String TOPOLOGY_INVALID = "TOPOLOGY_INVALID";

    public String conclusion;
    public int bound;
    public int exploredTransitions;
    public int reachableStates;
    public int mergedStates;
    public boolean limitReached;
    public String ruleVersion;
    public String topologyFingerprint;
    public String scenarioFingerprint;
    public Map<String, Integer> rejectionReasons = new TreeMap<>();
    public List<TraceStep> trace = new ArrayList<>();
    public List<String> topologyIssues = new ArrayList<>();
    public List<Map<String, Object>> stateSpaceSample = new ArrayList<>();

    public static final class TraceStep {
        public int step;
        public String actor;
        public String operation;
        public String trigger;
        public String effect;
        public boolean accepted;
        public String rejectReason;
        public List<String> violations = new ArrayList<>();
        public Map<String, Object> state;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step);
            m.put("actor", actor);
            m.put("operation", operation);
            m.put("trigger", trigger);
            m.put("effect", effect);
            m.put("accepted", accepted);
            m.put("rejectReason", rejectReason);
            m.put("violations", violations);
            m.put("state", state);
            return m;
        }
    }
}
