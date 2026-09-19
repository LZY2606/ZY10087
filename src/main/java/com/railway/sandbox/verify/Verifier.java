package com.railway.sandbox.verify;

import com.railway.sandbox.domain.Machine;
import com.railway.sandbox.domain.TopologyAnalysis;
import com.railway.sandbox.domain.TransitionResult;
import com.railway.sandbox.model.RuntimeState;
import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Explicit-state interleaving verifier.
 *
 * Each BFS node keeps one cursor per actor; enabled moves are each actor's
 * not-yet-fired next operation. Rejected operations consume the operation
 * (a rejection is an observable response) and never change state. Equal
 * canonical states are merged: the first path found in deterministic order
 * is retained, which simultaneously yields the lexicographically smallest
 * shortest counterexample on every machine.
 */
public final class Verifier {

    public static final int DEFAULT_BOUND = 5_000;
    public static final int HARD_BOUND = 200_000;

    public static final class Fingerprints {
        public static String sha256(String canonicalJson) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Node {
        RuntimeState state;
        int[] cursors;
        List<PathEntry> path;
    }

    private static final class PathEntry {
        int actorIdx;
        Scenario.Operation op;
        TransitionResult result;
    }

    private final Topology topology;
    private final RuleSet rules;
    private final Scenario scenario;
    private final Machine machine;

    public Verifier(Topology topology, RuleSet rules, Scenario scenario) {
        this.topology = topology;
        this.rules = rules;
        this.scenario = scenario;
        this.machine = new Machine(topology, rules);
    }

    public VerifyReport verify(int requestedBound) {
        int bound = Math.max(1, Math.min(requestedBound, HARD_BOUND));
        VerifyReport report = new VerifyReport();
        report.bound = bound;
        report.ruleVersion = rules.version;
        report.topologyFingerprint = Fingerprints.sha256(canonicalTopology());
        report.scenarioFingerprint = Fingerprints.sha256(canonicalScenario());

        TopologyAnalysis analysis = new TopologyAnalysis(topology);
        analysis.validate().forEach(i -> report.topologyIssues.add(i.code + " " + i.ref + " " + i.message));
        if (!report.topologyIssues.isEmpty()) {
            report.conclusion = VerifyReport.TOPOLOGY_INVALID;
            return report;
        }

        RuntimeState initial = machine.initialState(scenario);
        List<String> initialViolations = machine.invariants(initial);

        java.util.Map<String, Node> visited = new java.util.HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Node root = new Node();
        root.state = initial;
        root.cursors = new int[scenario.actors.size()];
        root.path = new ArrayList<>();
        queue.add(root);
        visited.put(initial.canonical(), root);
        report.reachableStates = 1;

        int transitions = 0;
        boolean limitReached = false;
        Node violatingNode = null;
        PathEntry violatingEntry = null;
        List<String> firstViolations = initialViolations;

        if (!initialViolations.isEmpty()) {
            violatingNode = root;
        }

        while (!queue.isEmpty() && violatingNode == null) {
            Node node = queue.poll();
            List<Move> moves = enabledMoves(node, scenario);
            for (Move move : moves) {
                if (transitions >= bound) { limitReached = true; break; }
                Scenario.Operation op = move.op;
                TransitionResult result = machine.fire(node.state, op);
                transitions++;
                tally(report, result);
                if (!result.accepted) {
                    if (transitions >= bound) { limitReached = true; break; }
                    continue;
                }
                RuntimeState ns = result.state;
                String canon = ns.canonical();
                Node existing = visited.get(canon);
                PathEntry entry = new PathEntry();
                entry.actorIdx = move.actorIdx;
                entry.op = op;
                entry.result = result;
                if (existing == null) {
                    Node child = new Node();
                    child.state = ns;
                    child.cursors = node.cursors.clone();
                    child.cursors[move.actorIdx]++;
                    child.path = new ArrayList<>(node.path);
                    child.path.add(entry);
                    visited.put(canon, child);
                    report.reachableStates++;
                    if (!result.violations.isEmpty()) {
                        violatingNode = child;
                        violatingEntry = entry;
                        firstViolations = result.violations;
                        break;
                    }
                    queue.add(child);
                } else {
                    report.mergedStates++;
                    if (!result.violations.isEmpty()) {
                        Node child = new Node();
                        child.state = ns;
                        child.cursors = node.cursors.clone();
                        child.cursors[move.actorIdx]++;
                        child.path = new ArrayList<>(node.path);
                        child.path.add(entry);
                        violatingNode = child;
                        violatingEntry = entry;
                        firstViolations = result.violations;
                        break;
                    }
                }
                if (transitions >= bound) { limitReached = true; break; }
            }
        }

        report.exploredTransitions = transitions;
        report.limitReached = limitReached;
        if (violatingNode != null) {
            report.conclusion = VerifyReport.COUNTEREXAMPLE;
            buildTrace(report, violatingNode, firstViolations);
        } else if (limitReached) {
            report.conclusion = VerifyReport.LIMIT_REACHED;
        } else {
            report.conclusion = VerifyReport.SAFE;
        }
        sampleStateSpace(visited, report);
        return report;
    }

    private void tally(VerifyReport report, TransitionResult r) {
        if (!r.accepted) {
            String key = stableRejectKey(r.rejectReason);
            report.rejectionReasons.merge(key, 1, Integer::sum);
        }
    }

    /** Collapse parameterised reasons into comparable codes across versions. */
    static String stableRejectKey(String reason) {
        if (reason == null) return "UNKNOWN";
        int colon = reason.indexOf(':');
        return colon > 0 ? reason.substring(0, colon) : reason;
    }

    private static final class Move {
        int actorIdx;
        Scenario.Operation op;
    }

    private List<Move> enabledMoves(Node node, Scenario sc) {
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < sc.actors.size(); i++) {
            Scenario.Actor actor = sc.actors.get(i);
            if (node.cursors[i] < actor.ops.size()) {
                Move m = new Move();
                m.actorIdx = i;
                m.op = actor.ops.get(node.cursors[i]);
                moves.add(m);
            }
        }
        moves.sort((a, b) -> {
            int ai = a.actorIdx, bi = b.actorIdx;
            String an = scenario.actors.get(ai).id;
            String bn = scenario.actors.get(bi).id;
            int c = an.compareTo(bn);
            if (c != 0) return c;
            return opKey(a.op).compareTo(opKey(b.op));
        });
        return moves;
    }

    static String opKey(Scenario.Operation op) {
        return op.op + "|" + (op.target == null ? "" : op.target)
                + "|" + (op.position == null ? "" : op.position.name());
    }

    private void buildTrace(VerifyReport report, Node node, List<String> finalViolations) {
        for (int i = 0; i < node.path.size(); i++) {
            PathEntry pe = node.path.get(i);
            TransitionResult tr = pe.result;
            VerifyReport.TraceStep step = new VerifyReport.TraceStep();
            step.step = i + 1;
            step.actor = scenario.actors.get(pe.actorIdx).id;
            step.operation = opKey(pe.op);
            step.trigger = tr.trigger;
            step.effect = tr.effect;
            step.accepted = tr.accepted;
            step.rejectReason = tr.rejectReason;
            step.violations = tr.violations;
            step.state = tr.accepted ? tr.state.snapshot() : null;
            report.trace.add(step);
        }
        if (node.path.isEmpty() && !finalViolations.isEmpty()) {
            VerifyReport.TraceStep zero = new VerifyReport.TraceStep();
            zero.step = 0;
            zero.operation = "INITIAL_STATE";
            zero.trigger = "初始状态即违反不变量";
            zero.effect = "无操作";
            zero.accepted = true;
            zero.violations = finalViolations;
            zero.state = node.state.snapshot();
            report.trace.add(zero);
        }
    }

    private void sampleStateSpace(java.util.Map<String, Node> visited, VerifyReport report) {
        List<String> keys = new ArrayList<>(visited.keySet());
        java.util.Collections.sort(keys);
        int max = Math.min(10, keys.size());
        for (int i = 0; i < max; i++) {
            Node n = visited.get(keys.get(i));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("depth", n.path.size());
            m.put("state", n.state.snapshot());
            report.stateSpaceSample.add(m);
        }
    }

    public String canonicalTopology() {
        Map<String, Object> m = new TreeMap<>();
        List<Object> ps = new ArrayList<>();
        topology.points.stream().sorted(java.util.Comparator.comparing(Topology.Point::id))
                .forEach(p -> ps.add(List.of(p.id(), p.boundary())));
        m.put("points", ps);
        List<Object> sws = new ArrayList<>();
        topology.switches.stream().sorted(java.util.Comparator.comparing(Topology.Switch::id))
                .forEach(s -> sws.add(List.of(s.id(), s.plus(), s.straight(), s.minus())));
        m.put("switches", sws);
        List<Object> secs = new ArrayList<>();
        topology.sections.stream().sorted(java.util.Comparator.comparing(Topology.Section::id))
                .forEach(s -> secs.add(List.of(s.id(), s.endA(), s.endB())));
        m.put("sections", secs);
        List<Object> rs = new ArrayList<>();
        topology.routes.stream().sorted(java.util.Comparator.comparing(Topology.RouteDef::id))
                .forEach(r -> {
                    Map<String, Object> rm = new TreeMap<>();
                    rm.put("id", r.id());
                    rm.put("signal", r.entrySignal());
                    rm.put("sections", new ArrayList<>(r.sections()));
                    Map<String, String> swp = new TreeMap<>();
                    r.switches().forEach((k, v) -> swp.put(k, v.name()));
                    rm.put("switches", swp);
                    rs.add(rm);
                });
        m.put("routes", rs);
        return JsonIO.write(m);
    }

    public String canonicalScenario() {
        Map<String, Object> m = new TreeMap<>();
        m.put("name", scenario.name);
        m.put("occupiedSections", new ArrayList<>(new java.util.TreeSet<>(scenario.occupiedSections)));
        m.put("failedSections", new ArrayList<>(new java.util.TreeSet<>(scenario.failedSections)));
        m.put("failedSwitches", new ArrayList<>(new java.util.TreeSet<>(scenario.failedSwitches)));
        Map<String, String> pos = new TreeMap<>();
        scenario.switchPositions.forEach((k, v) -> pos.put(k, v.name()));
        m.put("switchPositions", pos);
        List<Object> actors = new ArrayList<>();
        scenario.actors.forEach(a -> {
            Map<String, Object> am = new TreeMap<>();
            am.put("id", a.id);
            List<Object> ops = new ArrayList<>();
            a.ops.forEach(o -> {
                Map<String, String> om = new TreeMap<>();
                om.put("op", o.op);
                om.put("target", o.target);
                om.put("position", o.position == null ? null : o.position.name());
                ops.add(om);
            });
            am.put("ops", ops);
            actors.add(am);
        });
        m.put("actors", actors);
        return JsonIO.write(m);
    }
}
