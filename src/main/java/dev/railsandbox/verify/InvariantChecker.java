package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;

import java.util.*;

public final class InvariantChecker {
    private final Topology topology;
    private final RuleSet rules;
    private final Map<String, RouteDefinition> routes = new HashMap<>();
    private final Map<String, SignalDevice> signals = new HashMap<>();

    public InvariantChecker(Topology topology, RuleSet rules) {
        this.topology = topology;
        this.rules = rules;
        topology.routes().forEach(route -> routes.put(route.id(), route));
        topology.signals().forEach(signal -> signals.put(signal.id(), signal));
    }

    public List<InvariantViolation> check(RuntimeState state) {
        List<InvariantViolation> violations = new ArrayList<>();
        if (rules.mutualExclusion()) {
            checkMutualExclusion(state, violations);
        }
        if (rules.flankProtection()) {
            checkFlankProtection(state, violations);
        }
        checkReleasePrefix(state, violations);
        if (rules.signalProtection()) {
            checkSignals(state, violations);
        }
        checkFaultSafety(state, violations);
        return violations;
    }

    private void checkMutualExclusion(RuntimeState state, List<InvariantViolation> violations) {
        state.sectionOccupants().forEach((sectionId, occupants) -> {
            if (occupants.size() > 1) {
                violations.add(new InvariantViolation("互斥占用", "区段 " + sectionId + " 同时被 " + String.join(", ", new TreeSet<>(occupants)) + " 占用"));
            }
        });
        state.switchLockers().forEach((switchId, owners) -> {
            if (owners.size() > 1) {
                boolean oneFlankGuard = owners.stream().anyMatch(owner -> routeUsesFlankGuard(owner, switchId));
                boolean onlyFlankConflict = oneFlankGuard;
                String invariant = onlyFlankConflict ? "侧向防护" : "道岔互斥";
                String detail = (onlyFlankConflict ? "侧向防护道岔" : "道岔") + " " + switchId + " 同时被 "
                        + String.join(", ", new TreeSet<>(owners)) + " 锁闭";
                violations.add(new InvariantViolation(invariant, detail));
            }
        });
    }

    private boolean routeUsesPathSwitch(String routeId, String switchId) {
        RouteDefinition route = routes.get(routeId);
        if (route == null) {
            return false;
        }
        return route.explicitSwitches().stream().anyMatch(item -> item.switchId().equals(switchId));
    }

    private boolean routeUsesFlankGuard(String routeId, String switchId) {
        RouteDefinition route = routes.get(routeId);
        return route != null && route.explicitFlankGuards().stream().anyMatch(item -> item.switchId().equals(switchId));
    }

    private void checkFlankProtection(RuntimeState state, List<InvariantViolation> violations) {
        for (RouteDefinition route : topology.routes()) {
            RouteStatus status = state.routeStatus(route.id());
            boolean locked = status == RouteStatus.RESERVED || status == RouteStatus.ACTIVE
                    || status == RouteStatus.FAULTED || status == RouteStatus.RELEASED;
            if (!locked) {
                continue;
            }
            for (RequiredSwitch guard : route.explicitFlankGuards()) {
                if (state.faultySwitches().contains(guard.switchId())) {
                    violations.add(new InvariantViolation("侧向防护", "进路 " + route.id() + " 的防护道岔 " + guard.switchId() + " 故障"));
                } else if (state.switchPosition(guard.switchId()) != guard.position()) {
                    violations.add(new InvariantViolation("侧向防护", "进路 " + route.id() + " 的防护道岔 " + guard.switchId() + " 偏离要求位置"));
                } else if (!state.switchLockers(guard.switchId()).equals(Set.of(route.id()))
                        && (state.switchLockers(guard.switchId()).size() != 1 || !state.switchLockers(guard.switchId()).contains(route.id()))) {
                    violations.add(new InvariantViolation("侧向防护", "进路 " + route.id() + " 的防护道岔 " + guard.switchId() + " 未被本进路独占锁闭"));
                }
            }
        }
    }

    private void checkReleasePrefix(RuntimeState state, List<InvariantViolation> violations) {
        for (RouteDefinition route : topology.routes()) {
            SortedSet<String> released = state.released(route.id());
            for (int index = 0; index < route.sections().size(); index++) {
                boolean isReleased = released.contains(route.sections().get(index));
                boolean laterReleased = false;
                for (int later = index + 1; later < route.sections().size(); later++) {
                    laterReleased |= released.contains(route.sections().get(later));
                }
                if (!isReleased && laterReleased) {
                    violations.add(new InvariantViolation("释放顺序", "进路 " + route.id() + " 的已释放区段不是从入口开始的连续前缀，异常区段 " + route.sections().get(index)));
                    break;
                }
            }
        }
    }

    private void checkSignals(RuntimeState state, List<InvariantViolation> violations) {
        for (RouteDefinition route : topology.routes()) {
            RouteStatus status = state.routeStatus(route.id());
            SignalDevice signal = signals.get(route.entrySignal());
            if (signal == null) {
                continue;
            }
            if (state.faultySignals().contains(signal.id()) && (status == RouteStatus.RESERVED || status == RouteStatus.ACTIVE)) {
                violations.add(new InvariantViolation("信号防护", "进路 " + route.id() + " 的入口信号故障但进路仍保持授权"));
            }
            if (status == RouteStatus.RESERVED || status == RouteStatus.ACTIVE) {
                String first = route.sections().get(0);
                if (state.faultySections().contains(first)) {
                    violations.add(new InvariantViolation("信号防护", "进路 " + route.id() + " 首区段故障，入口信号必须停车"));
                }
            }
        }
    }

    private void checkFaultSafety(RuntimeState state, List<InvariantViolation> violations) {
        for (RouteDefinition route : topology.routes()) {
            RouteStatus status = state.routeStatus(route.id());
            boolean trainPresent = route.sections().stream().anyMatch(section -> state.occupants(section).contains(route.id()));
            boolean affected = route.sections().stream().anyMatch(state.faultySections()::contains)
                    || route.explicitSwitches().stream().map(RequiredSwitch::switchId).anyMatch(state.faultySwitches()::contains)
                    || route.explicitFlankGuards().stream().map(RequiredSwitch::switchId).anyMatch(state.faultySwitches()::contains)
                    || state.faultySignals().contains(route.entrySignal());
            if (affected && trainPresent && status != RouteStatus.FAULTED) {
                violations.add(new InvariantViolation("故障安全", "进路 " + route.id() + " 内有车且设备故障，但状态仍为 " + status + "，应为 FAULTED"));
            }
            if (affected && !trainPresent && (status == RouteStatus.RESERVED || status == RouteStatus.ACTIVE)) {
                violations.add(new InvariantViolation("故障安全", "进路 " + route.id() + " 受故障影响且无车，应撤销授权，当前为 " + status));
            }
        }
    }
}
