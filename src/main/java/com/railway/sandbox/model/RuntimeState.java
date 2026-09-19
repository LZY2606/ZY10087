package com.railway.sandbox.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mutable runtime state of one execution. Copy-on-branch is used by the
 * interleaving explorer. All maps are kept sorted so canonical fingerprints
 * are independent of insertion order across machines.
 */
public final class RuntimeState implements Cloneable {

    public enum RouteStatus { REQUESTED, FULLY_LOCKED, OCCUPIED, RELEASING, RELEASED }

    /** switch id -> position */
    public Map<String, Topology.SwitchPosition> switchPos = new TreeMap<>();
    public Set<String> occupied = new TreeSet<>();
    public Set<String> failedSections = new TreeSet<>();
    public Set<String> failedSwitches = new TreeSet<>();
    public Set<String> occupiedByTrain = new TreeSet<>();
    /** section id -> train id, for canonical symmetric state merging. */
    public Map<String, String> trainOn = new TreeMap<>();
    /** signal id -> true if a proceed aspect is shown */
    public Map<String, Boolean> signalClear = new TreeMap<>();

    public Map<String, RouteStatus> routes = new TreeMap<>();
    /** route id -> sections still locked (in entry-to-exit order) */
    public Map<String, List<String>> lockedSections = new TreeMap<>();
    public Map<String, Set<String>> lockedSwitches = new TreeMap<>();
    public Map<String, Set<String>> flankProtected = new TreeMap<>();
    /** routes currently rejected guard attempts, keyed by route id -> last reason */
    public Map<String, String> lastRejectReason = new TreeMap<>();

    public int stepCount;

    @Override
    public RuntimeState clone() {
        RuntimeState s;
        try {
            s = (RuntimeState) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
        s.switchPos = new TreeMap<>(switchPos);
        s.occupied = new TreeSet<>(occupied);
        s.failedSections = new TreeSet<>(failedSections);
        s.failedSwitches = new TreeSet<>(failedSwitches);
        s.occupiedByTrain = new TreeSet<>(occupiedByTrain);
        s.trainOn = new TreeMap<>(trainOn);
        s.signalClear = new TreeMap<>(signalClear);
        s.routes = new TreeMap<>(routes);
        s.lockedSections = new TreeMap<>();
        lockedSections.forEach((k, v) -> s.lockedSections.put(k, new ArrayList<>(v)));
        s.lockedSwitches = new TreeMap<>();
        lockedSwitches.forEach((k, v) -> s.lockedSwitches.put(k, new TreeSet<>(v)));
        s.flankProtected = new TreeMap<>();
        flankProtected.forEach((k, v) -> s.flankProtected.put(k, new TreeSet<>(v)));
        s.lastRejectReason = new TreeMap<>(lastRejectReason);
        return s;
    }

    /**
     * Canonical fingerprint for state merging. Train ids are symmetric:
     * only the multiset of "train patterns" matters, so states differing only
     * by renaming indistinguishable trains collapse to one canonical form
     * while concrete traces remain replayable.
     */
    public String canonical() {
        Map<String, List<String>> patternToTrains = new TreeMap<>();
        Map<String, List<String>> trainPatterns = new TreeMap<>();
        trainOn.forEach((section, train) ->
                trainPatterns.computeIfAbsent(train, k -> new ArrayList<>()).add(section));
        for (Map.Entry<String, List<String>> e : trainPatterns.entrySet()) {
            List<String> secs = new ArrayList<>(e.getValue());
            java.util.Collections.sort(secs);
            String pattern = String.join(",", secs);
            patternToTrains.computeIfAbsent(pattern, k -> new ArrayList<>()).add(e.getKey());
        }
        List<String> trainTokens = new ArrayList<>();
        patternToTrains.forEach((pattern, trains) -> trainTokens.add(
                "T[" + pattern + "]x" + trains.size()));
        return "pos=" + switchPos
                + "|occ=" + occupied
                + "|failS=" + failedSections
                + "|failW=" + failedSwitches
                + "|sig=" + signalClear
                + "|routes=" + routes
                + "|locked=" + lockedSections
                + "|lsw=" + lockedSwitches
                + "|flank=" + flankProtected
                + "|trains=" + trainTokens;
    }

    /** Full diagnostic snapshot (with concrete train labels), JSON-ready. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("switchPos", new TreeMap<>(switchPos));
        m.put("occupied", new TreeSet<>(occupied));
        m.put("failedSections", new TreeSet<>(failedSections));
        m.put("failedSwitches", new TreeSet<>(failedSwitches));
        m.put("trainOn", new TreeMap<>(trainOn));
        m.put("signalsClear", new TreeMap<>(signalClear));
        Map<String, String> rs = new TreeMap<>();
        routes.forEach((k, v) -> rs.put(k, v.name()));
        m.put("routes", rs);
        m.put("lockedSections", new TreeMap<>(lockedSections));
        m.put("lockedSwitches", new TreeMap<>(lockedSwitches));
        m.put("flankProtected", new TreeMap<>(flankProtected));
        m.put("step", stepCount);
        return m;
    }
}
