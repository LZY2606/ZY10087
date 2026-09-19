package com.railway.sandbox.domain;

import com.railway.sandbox.model.RuntimeState;
import com.railway.sandbox.model.RuntimeState.RouteStatus;
import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit interlocking state machine. One method per operation; every guard
 * produces a stable rejection code. Rule flags only relax guards — safety
 * invariants are evaluated by {@link #invariants(RuntimeState)} so that a
 * relaxed rule set demonstrably admits counterexamples.
 */
public final class Machine {

    private final Topology topology;
    private final RuleSet rules;
    private final TopologyAnalysis analysis;

    public Machine(Topology topology, RuleSet rules) {
        this.topology = topology;
        this.rules = rules;
        this.analysis = new TopologyAnalysis(topology);
    }

    public TopologyAnalysis analysis() { return analysis; }

    public Topology.RouteDef route(String id) {
        for (Topology.RouteDef r : topology.routes) if (r.id().equals(id)) return r;
        return null;
    }

    public Topology.Switch switchDef(String id) { return analysis.switchDef(id); }

    /** Sections currently locked by any FULLY_LOCKED / OCCUPIED / RELEASING route. */
    private Map<String, String> lockedBy(RuntimeState s) {
        java.util.Map<String, String> owner = new java.util.TreeMap<>();
        s.lockedSections.forEach((rid, secs) -> {
            RouteStatus st = s.routes.get(rid);
            if (st != null && st != RouteStatus.REQUESTED && st != RouteStatus.RELEASED)
                secs.forEach(x -> owner.putIfAbsent(x, rid));
        });
        return owner;
    }

    private List<String> otherActiveRoutes(RuntimeState s, String self) {
        List<String> out = new ArrayList<>();
        s.routes.forEach((rid, st) -> {
            if (!rid.equals(self) && (st == RouteStatus.FULLY_LOCKED
                    || st == RouteStatus.OCCUPIED || st == RouteStatus.RELEASING))
                out.add(rid);
        });
        return out;
    }

    public TransitionResult fire(RuntimeState state, Scenario.Operation op) {
        return switch (op.op) {
            case "REQUEST_ROUTE" -> requestRoute(state, op.target);
            case "CLEAR_SIGNAL" -> clearSignal(state, op.target);
            case "ENTER_SECTION" -> enterSection(state, op.target, op.actor);
            case "RELEASE_SECTION_NEXT" -> releaseNext(state, op.target);
            case "FINISH_ROUTE" -> finishRoute(state, op.target);
            case "MOVE_SWITCH" -> moveSwitch(state, op.target, op.position);
            case "INJECT_SECTION_FAULT" -> injectSectionFault(state, op.target);
            case "CLEAR_SECTION_FAULT" -> clearSectionFault(state, op.target);
            case "INJECT_SWITCH_FAULT" -> injectSwitchFault(state, op.target);
            case "CLEAR_SWITCH_FAULT" -> clearSwitchFault(state, op.target);
            default -> TransitionResult.rejected(state, "UNKNOWN_OPERATION",
                    "操作类型 " + op.op + " 未被状态机识别");
        };
    }

    public TransitionResult requestRoute(RuntimeState s, String routeId) {
        String trigger = "REQUEST_ROUTE " + routeId;
        Topology.RouteDef r = route(routeId);
        if (r == null)
            return TransitionResult.rejected(s, "ROUTE_NOT_FOUND", trigger);
        RouteStatus st = s.routes.get(routeId);
        if (st != null && st != RouteStatus.RELEASED)
            return TransitionResult.rejected(s, "ROUTE_ALREADY_ACTIVE",
                    trigger + "：进路当前状态为 " + st);

        List<String> why = new ArrayList<>();
        for (String sid : r.sections()) {
            if (s.failedSections.contains(sid))
                why.add("区段 " + sid + " 轨道电路故障");
            if (rules.requireSectionsFree && s.occupied.contains(sid))
                why.add("区段 " + sid + " 已被占用");
        }
        Map<String, String> owner = lockedBy(s);
        if (rules.enforceRouteMutex) {
            for (String sid : r.sections()) {
                if (owner.containsKey(sid))
                    why.add("区段 " + sid + " 已被进路 " + owner.get(sid) + " 锁闭");
            }
        }
        if (rules.enforceRouteMutex) {
            for (String other : otherActiveRoutes(s, routeId)) {
                Topology.RouteDef o = route(other);
                if (o == null) continue;
                for (String sid : r.sections()) {
                    if (o.sections().contains(sid)) {
                        why.add("与进路 " + other + " 存在区段冲突（互斥）: " + sid);
                        break;
                    }
                }
            }
        }
        for (Map.Entry<String, Topology.SwitchPosition> e : r.switches().entrySet()) {
            String swId = e.getKey();
            if (s.failedSwitches.contains(swId))
                why.add("道岔 " + swId + " 故障，无法锁闭");
            if (rules.requireSwitchPosition) {
                Topology.SwitchPosition cur = s.switchPos.get(swId);
                if (cur != e.getValue())
                    why.add("道岔 " + swId + " 当前位置 " + cur + "，要求 " + e.getValue());
            }
        }
        Set<String> flank = analysis.flankSections(r);
        if (rules.requireFlankProtection) {
            for (String sid : flank) {
                if (s.occupied.contains(sid))
                    why.add("侧向防护: 邻线区段 " + sid + " 被占用");
                if (owner.containsKey(sid))
                    why.add("侧向防护: 邻线区段 " + sid + " 被进路 " + owner.get(sid) + " 锁闭");
            }
        }
        if (!why.isEmpty())
            return TransitionResult.rejected(s, "GUARD_FAILED: " + String.join("; ", why), trigger);

        RuntimeState next = s.clone();
        next.routes.put(routeId, RouteStatus.FULLY_LOCKED);
        next.lockedSections.put(routeId, new ArrayList<>(r.sections()));
        Set<String> swSet = new java.util.TreeSet<>(r.switches().keySet());
        next.lockedSwitches.put(routeId, swSet);
        next.flankProtected.put(routeId, new java.util.TreeSet<>(flank));
        next.lastRejectReason.put(routeId, "");
        return finishTransition(s, next, trigger,
                "进路 " + routeId + " 锁闭完成: " + r.sections() + "，锁闭道岔 " + swSet);
    }

    public TransitionResult clearSignal(RuntimeState s, String signalId) {
        String trigger = "CLEAR_SIGNAL " + signalId;
        Topology.SignalDef sig = null;
        for (Topology.SignalDef d : topology.signals) if (d.id().equals(signalId)) sig = d;
        if (sig == null) return TransitionResult.rejected(s, "SIGNAL_NOT_FOUND", trigger);
        Topology.RouteDef r = route(routeOfSignal(signalId));
        String routeId = r == null ? null : r.id();
        if (routeId == null)
            return TransitionResult.rejected(s, "SIGNAL_WITHOUT_ROUTE",
                    trigger + "：没有以该信号机为入口的进路");
        RouteStatus st = s.routes.get(routeId);
        if (rules.signalRequiresFullLock && st != RouteStatus.FULLY_LOCKED
                && st != RouteStatus.OCCUPIED) {
            return TransitionResult.rejected(s, "SIGNAL_ROUTE_NOT_LOCKED",
                    trigger + "：进路 " + routeId + " 状态为 " + st + "，未完全锁闭");
        }
        if (s.failedSections.stream().anyMatch(r.sections()::contains))
            return TransitionResult.rejected(s, "SIGNAL_TRACK_FAULT",
                    trigger + "：进路内存在轨道电路故障");
        RuntimeState next = s.clone();
        next.signalClear.put(signalId, true);
        return finishTransition(s, next, trigger,
                "信号 " + signalId + " 开放（进路 " + routeId + "）");
    }

    public String routeOfSignal(String signalId) {
        for (Topology.RouteDef r : topology.routes)
            if (signalId.equals(r.entrySignal())) return r.id();
        return null;
    }

    /**
     * A train enters the named section. The section must belong to a route
     * the train is committed to and be the next locked section; the signal
     * must be clear. Trains are named by actor id so symmetric merging can
     * explain its equivalence.
     */
    public TransitionResult enterSection(RuntimeState s, String sectionId, String train) {
        String trigger = "ENTER_SECTION " + sectionId + " by " + train;
        Topology.Section sec = analysis.section(sectionId);
        if (sec == null) return TransitionResult.rejected(s, "SECTION_NOT_FOUND", trigger);
        String ownerRoute = null;
        List<String> locked = null;
        int index = -1;
        for (Map.Entry<String, List<String>> e : s.lockedSections.entrySet()) {
            int idx = e.getValue().indexOf(sectionId);
            if (idx >= 0) { ownerRoute = e.getKey(); locked = e.getValue(); index = idx; break; }
        }
        if (ownerRoute == null)
            return TransitionResult.rejected(s, "SECTION_NOT_LOCKED",
                    trigger + "：区段未被任何活动进路锁闭");
        RouteStatus st = s.routes.get(ownerRoute);
        if (st != RouteStatus.FULLY_LOCKED && st != RouteStatus.OCCUPIED)
            return TransitionResult.rejected(s, "ROUTE_NOT_SIGNALLABLE",
                    trigger + "：进路 " + ownerRoute + " 状态为 " + st);
        Topology.RouteDef rd = route(ownerRoute);
        String entrySignal = rd == null ? null : rd.entrySignal();
        if (index == 0 && Boolean.FALSE.equals(s.signalClear.get(entrySignal)))
            return TransitionResult.rejected(s, "SIGNAL_AT_RED",
                    trigger + "：入口信号 " + entrySignal + " 未开放");
        if (s.failedSections.contains(sectionId))
            return TransitionResult.rejected(s, "TRACK_FAULT",
                    trigger + "：区段轨道电路故障");

        RuntimeState next = s.clone();
        // Single-section train model: when the same train advances into the
        // next section its previous section is vacated automatically.
        String prevSection = null;
        for (Map.Entry<String, String> en : next.trainOn.entrySet()) {
            if ((train == null ? "T?" : train).equals(en.getValue())) { prevSection = en.getKey(); break; }
        }
        if (prevSection != null) {
            next.occupied.remove(prevSection);
            next.trainOn.remove(prevSection);
        }
        next.occupied.add(sectionId);
        next.trainOn.put(sectionId, train == null ? "T?" : train);
        next.routes.put(ownerRoute, RouteStatus.OCCUPIED);
        if (entrySignal != null) next.signalClear.put(entrySignal, false);
        return finishTransition(s, next, trigger,
                "列车 " + train + " 进入 " + sectionId
                        + (prevSection != null ? "，自动腾空 " + prevSection : "")
                        + "（进路 " + ownerRoute + "）");
    }

    /** Release the next still-locked section of a route (entry-to-exit order). */
    public TransitionResult releaseNext(RuntimeState s, String routeId) {
        String trigger = "RELEASE_SECTION_NEXT " + routeId;
        Topology.RouteDef r = route(routeId);
        if (r == null) return TransitionResult.rejected(s, "ROUTE_NOT_FOUND", trigger);
        RouteStatus st = s.routes.get(routeId);
        if (st != RouteStatus.OCCUPIED && st != RouteStatus.RELEASING)
            return TransitionResult.rejected(s, "ROUTE_NOT_OCCUPIED",
                    trigger + "：进路状态为 " + st + "，不能逐段释放");
        List<String> locked = s.lockedSections.get(routeId);
        if (locked == null || locked.isEmpty())
            return TransitionResult.rejected(s, "NOTHING_LOCKED", trigger);

        int nextIdx = -1;
        for (int i = 0; i < locked.size(); i++) {
            if (s.failedSections.contains(locked.get(i)))
                return TransitionResult.rejected(s, "TRACK_FAULT",
                        trigger + "：待释放区段 " + locked.get(i) + " 轨道电路故障");
        }
        for (int i = 0; i < locked.size(); i++) {
            String sid = locked.get(i);
            if (s.occupied.contains(sid)) { nextIdx = i; break; }
        }
        if (nextIdx == -1)
            return TransitionResult.rejected(s, "NO_TRAIN_ON_ROUTE",
                    trigger + "：进路上没有列车，应使用 FINISH_ROUTE 或先建立占用");
        if (rules.enforceReleaseOrder && nextIdx > 0)
            return TransitionResult.rejected(s, "RELEASE_ORDER_VIOLATION_ATTEMPT",
                    trigger + "：最前方未释放区段是 " + locked.get(0)
                            + "，不能跳过它释放 " + locked.get(nextIdx));
        String releasing = locked.get(nextIdx);
        if (rules.releaseOnlyAfterClear && s.occupied.contains(releasing))
            return TransitionResult.rejected(s, "RELEASE_WHILE_OCCUPIED",
                    trigger + "：区段 " + releasing + " 仍被列车占用，不能释放");

        RuntimeState next = s.clone();
        List<String> nl = new ArrayList<>(next.lockedSections.get(routeId));
        nl.remove(releasing);
        next.lockedSections.put(routeId, nl);
        next.routes.put(routeId, RouteStatus.RELEASING);
        return finishTransition(s, next, trigger,
                "进路 " + routeId + " 释放区段 " + releasing + "，剩余锁闭 " + nl);
    }

    public TransitionResult finishRoute(RuntimeState s, String routeId) {
        String trigger = "FINISH_ROUTE " + routeId;
        Topology.RouteDef r = route(routeId);
        if (r == null) return TransitionResult.rejected(s, "ROUTE_NOT_FOUND", trigger);
        RouteStatus st = s.routes.get(routeId);
        if (st == null || st == RouteStatus.RELEASED)
            return TransitionResult.rejected(s, "ROUTE_NOT_ACTIVE", trigger);
        List<String> locked = s.lockedSections.getOrDefault(routeId, List.of());
        if (rules.releaseOnlyAfterClear) {
            for (String sid : r.sections())
                if (s.occupied.contains(sid))
                    return TransitionResult.rejected(s, "RELEASE_WHILE_OCCUPIED",
                            trigger + "：区段 " + sid + " 仍被占用");
        }
        if (!locked.isEmpty())
            return TransitionResult.rejected(s, "SECTIONS_STILL_LOCKED",
                    trigger + "：仍有未逐段释放的区段 " + locked);
        RuntimeState next = s.clone();
        next.routes.put(routeId, RouteStatus.RELEASED);
        next.lockedSections.put(routeId, new ArrayList<>());
        next.lockedSwitches.put(routeId, new java.util.TreeSet<>());
        next.flankProtected.put(routeId, new java.util.TreeSet<>());
        return finishTransition(s, next, trigger, "进路 " + routeId + " 完全释放");
    }

    public TransitionResult moveSwitch(RuntimeState s, String switchId,
                                       Topology.SwitchPosition target) {
        String trigger = "MOVE_SWITCH " + switchId + " -> " + target;
        Topology.Switch sw = switchDef(switchId);
        if (sw == null) return TransitionResult.rejected(s, "SWITCH_NOT_FOUND", trigger);
        if (target == null) return TransitionResult.rejected(s, "POSITION_MISSING", trigger);
        if (s.failedSwitches.contains(switchId))
            return TransitionResult.rejected(s, "SWITCH_FAILED",
                    trigger + "：道岔故障，禁止扳动");
        if (rules.blockSwitchUnderMovement) {
            for (String sec : sectionsOnSwitchPorts(sw)) {
                if (s.occupied.contains(sec))
                    return TransitionResult.rejected(s, "SWITCH_OCCUPIED",
                            trigger + "：端口区段 " + sec + " 有车占用");
            }
            for (Map.Entry<String, Set<String>> e : s.lockedSwitches.entrySet()) {
                RouteStatus st = s.routes.get(e.getKey());
                if (e.getValue().contains(switchId) && st != RouteStatus.RELEASED)
                    return TransitionResult.rejected(s, "SWITCH_LOCKED",
                            trigger + "：道岔已被进路 " + e.getKey() + " 锁闭");
            }
        }
        RuntimeState next = s.clone();
        next.switchPos.put(switchId, target);
        return finishTransition(s, next, trigger, "道岔 " + switchId + " 扳至 " + target);
    }

    private List<String> sectionsOnSwitchPorts(Topology.Switch sw) {
        List<String> out = new ArrayList<>();
        for (Topology.Section sec : topology.sections) {
            if (swPorts(sw).contains(sec.endA()) || swPorts(sw).contains(sec.endB()))
                out.add(sec.id());
        }
        return out;
    }

    private java.util.Set<String> swPorts(Topology.Switch sw) {
        return java.util.Set.of(sw.plus(), sw.straight(), sw.minus());
    }

    public TransitionResult injectSectionFault(RuntimeState s, String sectionId) {
        String trigger = "INJECT_SECTION_FAULT " + sectionId;
        if (analysis.section(sectionId) == null)
            return TransitionResult.rejected(s, "SECTION_NOT_FOUND", trigger);
        RuntimeState next = s.clone();
        next.failedSections.add(sectionId);
        return finishTransition(s, next, trigger, "区段 " + sectionId + " 插入设备故障");
    }

    public TransitionResult clearSectionFault(RuntimeState s, String sectionId) {
        String trigger = "CLEAR_SECTION_FAULT " + sectionId;
        if (!s.failedSections.contains(sectionId))
            return TransitionResult.rejected(s, "NO_SUCH_FAULT", trigger);
        RuntimeState next = s.clone();
        next.failedSections.remove(sectionId);
        return finishTransition(s, next, trigger, "区段 " + sectionId + " 故障恢复");
    }

    public TransitionResult injectSwitchFault(RuntimeState s, String switchId) {
        String trigger = "INJECT_SWITCH_FAULT " + switchId;
        if (switchDef(switchId) == null)
            return TransitionResult.rejected(s, "SWITCH_NOT_FOUND", trigger);
        RuntimeState next = s.clone();
        next.failedSwitches.add(switchId);
        return finishTransition(s, next, trigger, "道岔 " + switchId + " 插入设备故障");
    }

    public TransitionResult clearSwitchFault(RuntimeState s, String switchId) {
        String trigger = "CLEAR_SWITCH_FAULT " + switchId;
        if (!s.failedSwitches.contains(switchId))
            return TransitionResult.rejected(s, "NO_SUCH_FAULT", trigger);
        RuntimeState next = s.clone();
        next.failedSwitches.remove(switchId);
        return finishTransition(s, next, trigger, "道岔 " + switchId + " 故障恢复");
    }

    private TransitionResult finishTransition(RuntimeState before, RuntimeState next,
                                              String trigger, String effect) {
        next.stepCount = before.stepCount + 1;
        TransitionResult r = new TransitionResult();
        r.accepted = true;
        r.state = next;
        r.trigger = trigger;
        r.effect = effect;
        r.violations = invariants(next);
        return r;
    }

    /**
     * Safety invariants evaluated after every accepted transition. These are
     * independent of rule flags: a counterexample here means the deployed rule
     * set failed to prevent the hazard.
     *
     *  I1 MUTEX         : a section is locked by at most one active route
     *  I2 OCCUPANCY     : an occupied section is always locked by exactly one route
     *  I3 FLANK         : while a diverging route is occupied, no flank section is
     *  I4 RELEASE_ORDER : sections of a route release strictly entry-to-exit
     *  I5 SIGNAL        : a clear signal guards a fully locked, fault-free route
     *  I6 SWITCH_LOCK   : a locked switch never moves position away from the
     *                     position the route required
     */
    public List<String> invariants(RuntimeState s) {
        List<String> v = new ArrayList<>();
        java.util.Map<String, List<String>> lockOwners = new java.util.TreeMap<>();
        s.lockedSections.forEach((rid, secs) -> {
            RouteStatus st = s.routes.get(rid);
            if (st != null && st != RouteStatus.RELEASED)
                secs.forEach(x -> lockOwners.computeIfAbsent(x, k -> new ArrayList<>()).add(rid));
        });
        lockOwners.forEach((sec, owners) -> {
            if (owners.size() > 1)
                v.add("I1 MUTEX: 区段 " + sec + " 同时被多条进路锁闭 " + owners);
        });
        s.occupied.forEach(sec -> {
            List<String> owners = lockOwners.getOrDefault(sec, List.of());
            if (owners.isEmpty())
                v.add("I2 OCCUPANCY: 区段 " + sec + " 有车占用却没有任何进路锁闭");
        });
        s.routes.forEach((rid, st) -> {
            if (st == RouteStatus.OCCUPIED) {
                Topology.RouteDef r = route(rid);
                if (r != null) {
                    Set<String> flank = analysis.flankSections(r);
                    for (String fsec : flank) {
                        if (s.occupied.contains(fsec))
                            v.add("I3 FLANK: 进路 " + rid + " 占用时侧向区段 " + fsec + " 也被占用");
                        List<String> fo = lockOwners.get(fsec);
                        if (fo != null && !fo.equals(List.of(rid)))
                            v.add("I3 FLANK: 进路 " + rid + " 占用时侧向区段 " + fsec
                                    + " 被进路 " + fo + " 锁闭");
                    }
                }
            }
        });
        s.lockedSections.forEach((rid, locked) -> {
            Topology.RouteDef r = route(rid);
            if (r == null || locked.isEmpty()) return;
            List<String> all = r.sections();
            String firstLocked = locked.get(0);
            int firstIdx = all.indexOf(firstLocked);
            for (String sid : locked) {
                if (all.indexOf(sid) < firstIdx) {
                    v.add("I4 RELEASE_ORDER: 进路 " + rid + " 释放顺序错误（前缀区段已释放而后缀仍锁闭）");
                    break;
                }
            }
        });
        s.signalClear.forEach((sig, clear) -> {
            if (!Boolean.TRUE.equals(clear)) return;
            String rid = routeOfSignal(sig);
            if (rid == null) {
                v.add("I5 SIGNAL: 信号 " + sig + " 开放但无对应进路");
                return;
            }
            RouteStatus st = s.routes.get(rid);
            if (st != RouteStatus.FULLY_LOCKED && st != RouteStatus.OCCUPIED)
                v.add("I5 SIGNAL: 信号 " + sig + " 开放但进路 " + rid + " 状态为 " + st);
            Topology.RouteDef r = route(rid);
            if (r != null && r.sections().stream().anyMatch(s.failedSections::contains))
                v.add("I5 SIGNAL: 信号 " + sig + " 开放但进路内有轨道电路故障");
        });
        s.lockedSwitches.forEach((rid, sws) -> {
            RouteStatus st = s.routes.get(rid);
            if (st == RouteStatus.RELEASED) return;
            Topology.RouteDef r = route(rid);
            if (r == null) return;
            for (String swId : sws) {
                Topology.SwitchPosition required = r.switches().get(swId);
                Topology.SwitchPosition actual = s.switchPos.get(swId);
                if (required != null && actual != required)
                    v.add("I6 SWITCH_LOCK: 进路 " + rid + " 锁闭的道岔 " + swId
                            + " 实际位置 " + actual + " 偏离要求位置 " + required);
            }
        });
        return v;
    }

    /** Build initial runtime state from a scenario. */
    public RuntimeState initialState(Scenario sc) {
        RuntimeState s = new RuntimeState();
        for (Topology.Switch sw : topology.switches)
            s.switchPos.put(sw.id(), Topology.SwitchPosition.PLUS);
        if (sc.switchPositions != null) s.switchPos.putAll(sc.switchPositions);
        if (sc.occupiedSections != null) {
            int i = 0;
            for (String sec : sc.occupiedSections) {
                s.occupied.add(sec);
                String train = "INIT" + (i++);
                s.trainOn.put(sec, train);
            }
        }
        if (sc.failedSections != null) s.failedSections.addAll(sc.failedSections);
        if (sc.failedSwitches != null) s.failedSwitches.addAll(sc.failedSwitches);
        for (Topology.SignalDef sig : topology.signals) s.signalClear.put(sig.id(), false);
        return s;
    }
}
