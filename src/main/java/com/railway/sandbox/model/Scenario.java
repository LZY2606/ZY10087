package com.railway.sandbox.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scenario is the verification workload: initial runtime state plus a pool
 * of operations performed by concurrent actors. The verifier interleaves
 * each actor's operations while preserving per-actor order.
 */
public final class Scenario {
    public String name = "scenario";
    public String description = "";
    /** Initial switch positions, keyed by switch id. */
    public Map<String, Topology.SwitchPosition> switchPositions = new LinkedHashMap<>();
    /** Initially occupied section ids (e.g. standing trains). */
    public List<String> occupiedSections = new ArrayList<>();
    /** Sections initially failed (track circuit fault). */
    public List<String> failedSections = new ArrayList<>();
    /** Switches initially failed (cannot be moved, cannot be locked). */
    public List<String> failedSwitches = new ArrayList<>();
    /** One operation program per actor; order inside an actor is preserved. */
    public List<Actor> actors = new ArrayList<>();

    public static final class Actor {
        public String id;
        public List<Operation> ops = new ArrayList<>();

        public Actor() {}
        public Actor(String id) { this.id = id; }
    }

    public static final class Operation {
        public String op;
        public String target;
        public Topology.SwitchPosition position;
        public String actor;
        /** Optional deterministic note shown in diagnostics. */
        public String note;

        public Operation() {}
        public Operation(String op, String target) { this.op = op; this.target = target; }
        public Operation(String op, String target, Topology.SwitchPosition position) {
            this.op = op;
            this.target = target;
            this.position = position;
        }
        public static Operation move(String sw, Topology.SwitchPosition p) {
            Operation o = new Operation("MOVE_SWITCH", sw);
            o.position = p;
            return o;
        }
    }
}
