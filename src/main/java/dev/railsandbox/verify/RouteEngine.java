package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;

import java.util.*;

public final class RouteEngine {
    private final Topology topology;
    private final RuleSet rules;
    private final Map<String, RouteDefinition> routes = new HashMap<>();
    private final Map<String, SwitchDevice> switches = new HashMap<>();
    private final Map<String, SignalDevice> signals = new HashMap<>();

    public RouteEngine(Topology topology, RuleSet rules) {
        this.topology = topology;
        this.rules = rules;
        topology.routes().forEach(route -> routes.put(route.id(), route));
        topology.switches().forEach(sw -> switches.put(sw.id(), sw));
        topology.signals().forEach(signal -> signals.put(signal.id(), signal));
    }

    public TransitionResult apply(RuntimeState state, ScenarioAction action) {
        return switch (action.type()) {
            case REQUEST_ROUTE -> request(state, action);
            case OCCUPY_SECTION -> occupy(state, action);
            case RELEASE_SECTION -> release(state, action);
            case CANCEL_ROUTE -> cancel(state, action);
            case DEVICE_FAULT -> fault(state, action);
            case DEVICE_REPAIR -> repair(state, action);
        };
    }

    private TransitionResult request(RuntimeState state, ScenarioAction action) {
        RouteDefinition route = routes.get(action.routeId());
        List<GuardCheck> checks = new ArrayList<>();
        if (route == null) {
            return TransitionResult.rejected("UNKNOWN_ROUTE", "进路不存在：" + action.routeId(), List.of());
        }
        checks.add(GuardCheck.of(state.routeStatus(route.id()) == RouteStatus.IDLE,
                "状态机", "进路处于 IDLE，允许请求"));
        if (state.routeStatus(route.id()) != RouteStatus.IDLE) {
            return TransitionResult.rejected("INVALID_STATE", "进路只能在 IDLE 状态请求，当前为 " + state.routeStatus(route.id()), checks);
        }
        SignalDevice signal = signals.get(route.entrySignal());
        checks.add(GuardCheck.of(signal != null && !state.faultySignals().contains(signal.id()),
                "入口信号可用", signal == null ? "入口信号不存在" : "入口信号 " + signal.id() + " 未故障"));
        if (signal != null && state.faultySignals().contains(signal.id())) {
            return TransitionResult.rejected("ENTRY_SIGNAL_FAULT", "入口信号 " + signal.id() + " 故障，不能建立进路", checks);
        }
        for (String sectionId : route.sections()) {
            boolean faulty = state.faultySections().contains(sectionId);
            boolean occupied = !state.occupants(sectionId).isEmpty();
            boolean free = !rules.mutualExclusion() || state.lockers(sectionId).isEmpty();
            checks.add(GuardCheck.of(!faulty, "区段健康", "区段 " + sectionId + (faulty ? " 故障" : " 未故障")));
            checks.add(GuardCheck.of(!occupied, "区段空闲", "区段 " + sectionId + (occupied ? " 已被占用" : " 未被占用")));
            checks.add(GuardCheck.of(free, "互斥锁闭", "区段 " + sectionId + (free ? " 未锁闭" : " 已被其他进路锁闭")));
            if (faulty) {
                return TransitionResult.rejected("SECTION_FAULT", "区段 " + sectionId + " 故障，不能建立进路", checks);
            }
            if (occupied) {
                return TransitionResult.rejected("SECTION_OCCUPIED", "区段 " + sectionId + " 被占用，不能建立进路", checks);
            }
            if (!free) {
                return TransitionResult.rejected("MUTEX_DENIED", "区段 " + sectionId + " 已被其他进路锁闭，违反互斥", checks);
            }
        }
        for (RequiredSwitch required : requiredSwitches(route)) {
            SwitchDevice sw = switches.get(required.switchId());
            boolean exists = sw != null;
            boolean faulty = state.faultySwitches().contains(required.switchId());
            boolean available = !rules.mutualExclusion() || state.switchLockers(required.switchId()).isEmpty()
                    || state.switchLockers(required.switchId()).equals(Set.of(route.id()));
            checks.add(GuardCheck.of(exists, "道岔存在", "道岔 " + required.switchId() + (exists ? " 存在" : " 不存在")));
            checks.add(GuardCheck.of(!faulty, "道岔健康", "道岔 " + required.switchId() + (faulty ? " 故障" : " 未故障")));
            checks.add(GuardCheck.of(available, "道岔锁互斥", "道岔 " + required.switchId() + (available ? " 可用" : " 已被其他进路锁闭")));
            if (!exists) {
                return TransitionResult.rejected("UNKNOWN_SWITCH", "道岔不存在：" + required.switchId(), checks);
            }
            if (faulty) {
                return TransitionResult.rejected("SWITCH_FAULT", "道岔 " + required.switchId() + " 故障，不能建立进路", checks);
            }
            if (!available) {
                return TransitionResult.rejected("SWITCH_LOCKED", "道岔 " + required.switchId() + " 已被其他进路锁闭", checks);
            }
        }
        for (RequiredSwitch guard : route.explicitFlankGuards()) {
            boolean faulty = state.faultySwitches().contains(guard.switchId());
            boolean position = state.switchPosition(guard.switchId()) == guard.position();
            boolean switchMutex = rules.mutualExclusion() && rules.flankProtection();
            boolean available = !switchMutex || state.switchLockers(guard.switchId()).isEmpty()
                    || state.switchLockers(guard.switchId()).equals(Set.of(route.id()));
            boolean satisfied = !rules.flankProtection() || (!faulty && position && available);
            checks.add(GuardCheck.of(satisfied, "侧向防护", "防护道岔 " + guard.switchId()
                    + " 要求 " + guard.position() + "，实际 " + state.switchPosition(guard.switchId())
                    + (faulty ? "，且设备故障" : "")));
            if (rules.flankProtection() && faulty) {
                return TransitionResult.rejected("FLANK_GUARD_FAULT", "侧向防护道岔 " + guard.switchId() + " 故障", checks);
            }
            if (rules.flankProtection() && !position) {
                return TransitionResult.rejected("FLANK_GUARD_POSITION", "侧向防护道岔 " + guard.switchId() + " 未在要求位置", checks);
            }
            if (rules.flankProtection() && !available) {
                return TransitionResult.rejected("FLANK_GUARD_LOCKED", "侧向防护道岔 " + guard.switchId() + " 被冲突进路锁闭", checks);
            }
        }
        for (RequiredSwitch required : requiredSwitches(route)) {
            state.switchPosition(required.switchId(), required.position());
            state.switchLockers(required.switchId()).add(route.id());
        }
        for (RequiredSwitch guard : route.explicitFlankGuards()) {
            state.switchPosition(guard.switchId(), guard.position());
            state.switchLockers(guard.switchId()).add(route.id());
        }
        for (String sectionId : route.sections()) {
            state.lockers(sectionId).add(route.id());
        }
        state.previousStatus(route.id(), state.routeStatus(route.id()));
        state.routeStatus(route.id(), RouteStatus.RESERVED);
        List<String> effects = new ArrayList<>();
        effects.add("锁闭进路区段：" + String.join(", ", route.sections()));
        effects.add("置位并锁闭道岔与侧向防护");
        effects.add("状态 IDLE -> RESERVED");
        return TransitionResult.accepted(checks, effects);
    }

    private List<RequiredSwitch> requiredSwitches(RouteDefinition route) {
        return route.explicitSwitches();
    }

    private TransitionResult occupy(RuntimeState state, ScenarioAction action) {
        RouteDefinition route = routes.get(action.routeId());
        List<GuardCheck> checks = new ArrayList<>();
        if (route == null) {
            return TransitionResult.rejected("UNKNOWN_ROUTE", "进路不存在：" + action.routeId(), List.of());
        }
        int index = route.sections().indexOf(action.sectionId());
        RouteStatus status = state.routeStatus(route.id());
        boolean stateAllowed = status == RouteStatus.RESERVED || status == RouteStatus.ACTIVE;
        checks.add(GuardCheck.of(index >= 0, "区段属于进路", action.sectionId() + (index >= 0 ? " 属于 " : " 不属于 ") + route.id()));
        checks.add(GuardCheck.of(stateAllowed, "状态机", "进路处于 " + status + "，占用要求 RESERVED 或 ACTIVE"));
        if (index < 0) {
            return TransitionResult.rejected("UNKNOWN_SECTION", "进路不包含区段 " + action.sectionId(), checks);
        }
        if (!stateAllowed) {
            return TransitionResult.rejected("INVALID_STATE", "当前状态 " + status + " 不允许占用", checks);
        }
        int expectedIndex = 0;
        while (expectedIndex < route.sections().size()
                && (state.occupants(route.sections().get(expectedIndex)).contains(route.id())
                || state.released(route.id()).contains(route.sections().get(expectedIndex)))) {
            expectedIndex++;
        }
        boolean released = state.released(route.id()).contains(action.sectionId());
        boolean nextInOrder = !released && index == expectedIndex;
        checks.add(GuardCheck.of(nextInOrder, "占用顺序", "下一列应占用序号 " + expectedIndex + "，收到 " + index));
        if (!nextInOrder) {
            return TransitionResult.rejected("OCCUPY_ORDER", "只能按进路方向顺序占用下一区段", checks);
        }
        boolean locked = state.lockers(action.sectionId()).contains(route.id());
        boolean faulty = state.faultySections().contains(action.sectionId());
        boolean mutex = !rules.mutualExclusion() || state.occupants(action.sectionId()).isEmpty();
        checks.add(GuardCheck.of(locked, "锁闭保持", action.sectionId() + (locked ? " 仍由本进路锁闭" : " 已失去本进路锁闭")));
        checks.add(GuardCheck.of(!faulty, "区段健康", action.sectionId() + (faulty ? " 故障" : " 未故障")));
        checks.add(GuardCheck.of(mutex, "占用互斥", action.sectionId() + (mutex ? " 无其他列车" : " 已被其他进路占用")));
        if (!locked) {
            return TransitionResult.rejected("LOCK_LOST", "区段已失去本进路锁闭", checks);
        }
        if (faulty) {
            return TransitionResult.rejected("SECTION_FAULT_OCCUPIED", "区段故障，拒绝占用；请由设备故障动作驱动状态机迁移", checks);
        }
        if (!mutex) {
            return TransitionResult.rejected("OCCUPANCY_CONFLICT", "区段已被其他进路占用", checks);
        }
        state.occupants(action.sectionId()).add(route.id());
        state.previousStatus(route.id(), status);
        state.routeStatus(route.id(), RouteStatus.ACTIVE);
        return TransitionResult.accepted(checks, List.of(
                "列车占用 " + action.sectionId(),
                (status == RouteStatus.RESERVED ? "状态 RESERVED -> ACTIVE" : "状态保持 ACTIVE")
        ));
    }

    private TransitionResult release(RuntimeState state, ScenarioAction action) {
        RouteDefinition route = routes.get(action.routeId());
        List<GuardCheck> checks = new ArrayList<>();
        if (route == null) {
            return TransitionResult.rejected("UNKNOWN_ROUTE", "进路不存在：" + action.routeId(), List.of());
        }
        int index = route.sections().indexOf(action.sectionId());
        RouteStatus status = state.routeStatus(route.id());
        boolean stateAllowed = status == RouteStatus.ACTIVE;
        checks.add(GuardCheck.of(index >= 0, "区段属于进路", action.sectionId() + (index >= 0 ? " 属于 " : " 不属于 ") + route.id()));
        checks.add(GuardCheck.of(stateAllowed, "状态机", "进路处于 " + status + "，释放要求 ACTIVE"));
        if (index < 0 || !stateAllowed) {
            return TransitionResult.rejected("INVALID_STATE", "当前状态不允许释放 " + action.sectionId(), checks);
        }
        boolean occupiedByRoute = state.occupants(action.sectionId()).contains(route.id());
        boolean alreadyReleased = state.released(route.id()).contains(action.sectionId());
        checks.add(GuardCheck.of(occupiedByRoute, "占用凭证", action.sectionId() + " 由本进路占用"));
        if (!occupiedByRoute || alreadyReleased) {
            return TransitionResult.rejected("NOT_OCCUPIED", "不能释放未占用区段", checks);
        }
        if (rules.orderedRelease()) {
            boolean ordered = index == 0 || state.released(route.id()).contains(route.sections().get(index - 1));
            checks.add(GuardCheck.of(ordered, "释放顺序", ordered ? "前一区段已经释放" : "前一区段尚未释放"));
            if (!ordered) {
                return TransitionResult.rejected("RELEASE_ORDER", "必须从首区段开始按顺序释放", checks);
            }
        }
        state.occupants(action.sectionId()).remove(route.id());
        state.lockers(action.sectionId()).remove(route.id());
        state.released(route.id()).add(action.sectionId());
        boolean complete = route.sections().stream().allMatch(section -> state.released(route.id()).contains(section));
        if (complete) {
            for (RequiredSwitch required : requiredSwitches(route)) {
                state.switchLockers(required.switchId()).remove(route.id());
            }
            for (RequiredSwitch guard : route.explicitFlankGuards()) {
                state.switchLockers(guard.switchId()).remove(route.id());
            }
            state.previousStatus(route.id(), status);
            state.routeStatus(route.id(), RouteStatus.RELEASED);
            return TransitionResult.accepted(checks, List.of("释放 " + action.sectionId() + " 并解除其锁闭", "状态 ACTIVE -> RELEASED"));
        }
        return TransitionResult.accepted(checks, List.of("释放 " + action.sectionId() + "，本进路后续区段保持锁闭", "状态保持 ACTIVE"));
    }

    private TransitionResult cancel(RuntimeState state, ScenarioAction action) {
        RouteDefinition route = routes.get(action.routeId());
        List<GuardCheck> checks = new ArrayList<>();
        if (route == null) {
            return TransitionResult.rejected("UNKNOWN_ROUTE", "进路不存在：" + action.routeId(), List.of());
        }
        RouteStatus status = state.routeStatus(route.id());
        boolean trainPresent = route.sections().stream().anyMatch(section -> state.occupants(section).contains(route.id()));
        checks.add(GuardCheck.of(status == RouteStatus.RESERVED, "状态机", "取消要求 RESERVED，当前为 " + status));
        checks.add(GuardCheck.of(!trainPresent, "无车占用", trainPresent ? "进路内仍有列车占用" : "进路内无列车"));
        if (status != RouteStatus.RESERVED || trainPresent) {
            return TransitionResult.rejected("CANCEL_DENIED", "只有未占用的 RESERVED 进路可以取消", checks);
        }
        releaseRouteResources(state, route);
        state.previousStatus(route.id(), status);
        state.routeStatus(route.id(), RouteStatus.IDLE);
        return TransitionResult.accepted(checks, List.of("解除进路锁闭和道岔锁", "状态 RESERVED -> IDLE"));
    }

    private TransitionResult fault(RuntimeState state, ScenarioAction action) {
        String deviceId = action.deviceId();
        List<GuardCheck> checks = new ArrayList<>();
        boolean section = topology.sections().stream().anyMatch(item -> item.id().equals(deviceId));
        boolean sw = switches.containsKey(deviceId);
        boolean signal = signals.containsKey(deviceId);
        checks.add(GuardCheck.of(section || sw || signal, "设备存在", deviceId + (section || sw || signal ? " 存在" : " 不存在")));
        if (!section && !sw && !signal) {
            return TransitionResult.rejected("UNKNOWN_DEVICE", "设备不存在：" + deviceId, checks);
        }
        if (section) {
            state.faultySections().add(deviceId);
            markRoutesFaulted(state, checks, route -> route.sections().contains(deviceId));
            return TransitionResult.accepted(checks, List.of("区段 " + deviceId + " 标记故障", "受影响占用进路转入 FAULTED"));
        }
        if (sw) {
            state.faultySwitches().add(deviceId);
            markRoutesFaulted(state, checks, route -> usesSwitch(route, deviceId));
            return TransitionResult.accepted(checks, List.of("道岔 " + deviceId + " 标记故障", "受影响占用进路转入 FAULTED"));
        }
        state.faultySignals().add(deviceId);
        markRoutesFaulted(state, checks, route -> route.entrySignal().equals(deviceId));
        return TransitionResult.accepted(checks, List.of("信号 " + deviceId + " 标记故障", "受影响占用进路转入 FAULTED"));
    }

    private TransitionResult repair(RuntimeState state, ScenarioAction action) {
        String deviceId = action.deviceId();
        List<GuardCheck> checks = new ArrayList<>();
        boolean knownFault = state.faultySections().contains(deviceId)
                || state.faultySwitches().contains(deviceId)
                || state.faultySignals().contains(deviceId);
        checks.add(GuardCheck.of(knownFault, "故障存在", deviceId + (knownFault ? " 处于故障" : " 未处于故障")));
        if (!knownFault) {
            return TransitionResult.rejected("NOT_FAULTED", "设备没有待恢复故障：" + deviceId, checks);
        }
        state.faultySections().remove(deviceId);
        state.faultySwitches().remove(deviceId);
        state.faultySignals().remove(deviceId);
        for (RouteDefinition route : routes.values()) {
            boolean affected = route.sections().contains(deviceId) || usesSwitch(route, deviceId) || route.entrySignal().equals(deviceId);
            boolean trainPresent = route.sections().stream().anyMatch(section -> state.occupants(section).contains(route.id()));
            if (affected && state.routeStatus(route.id()) == RouteStatus.FAULTED && trainPresent) {
                state.previousStatus(route.id(), RouteStatus.FAULTED);
                state.routeStatus(route.id(), RouteStatus.ACTIVE);
                checks.add(GuardCheck.of(true, "进路恢复", route.id() + " 内仍有列车，FAULTED -> ACTIVE"));
            } else if (affected && state.routeStatus(route.id()) == RouteStatus.FAULTED) {
                releaseRouteResources(state, route);
                state.previousStatus(route.id(), RouteStatus.FAULTED);
                state.routeStatus(route.id(), RouteStatus.IDLE);
                checks.add(GuardCheck.of(true, "进路恢复", route.id() + " 内无车，FAULTED -> IDLE 并解除锁闭"));
            }
        }
        return TransitionResult.accepted(checks, List.of("设备 " + deviceId + " 恢复", "按故障安全原则处理受影响进路"));
    }

    private void markRoutesFaulted(RuntimeState state, List<GuardCheck> checks, java.util.function.Predicate<RouteDefinition> affected) {
        for (RouteDefinition route : routes.values()) {
            boolean trainPresent = route.sections().stream().anyMatch(section -> state.occupants(section).contains(route.id()));
            if (affected.test(route) && trainPresent && state.routeStatus(route.id()) != RouteStatus.FAULTED) {
                state.previousStatus(route.id(), state.routeStatus(route.id()));
                state.routeStatus(route.id(), RouteStatus.FAULTED);
                checks.add(GuardCheck.of(true, "故障安全", route.id() + " 有车占用，状态 -> FAULTED"));
            } else if (affected.test(route) && !trainPresent
                    && (state.routeStatus(route.id()) == RouteStatus.RESERVED || state.routeStatus(route.id()) == RouteStatus.ACTIVE)) {
                releaseRouteResources(state, route);
                state.previousStatus(route.id(), state.routeStatus(route.id()));
                state.routeStatus(route.id(), RouteStatus.IDLE);
                checks.add(GuardCheck.of(true, "故障安全", route.id() + " 无车占用，撤销授权并解除锁闭 -> IDLE"));
            }
        }
    }

    private boolean usesSwitch(RouteDefinition route, String switchId) {
        return route.explicitSwitches().stream().anyMatch(item -> item.switchId().equals(switchId))
                || route.explicitFlankGuards().stream().anyMatch(item -> item.switchId().equals(switchId));
    }

    private void releaseRouteResources(RuntimeState state, RouteDefinition route) {
        for (String sectionId : route.sections()) {
            state.lockers(sectionId).remove(route.id());
            state.occupants(sectionId).remove(route.id());
            state.released(route.id()).add(sectionId);
        }
        for (RequiredSwitch required : requiredSwitches(route)) {
            state.switchLockers(required.switchId()).remove(route.id());
        }
        for (RequiredSwitch guard : route.explicitFlankGuards()) {
            state.switchLockers(guard.switchId()).remove(route.id());
        }
    }
}
