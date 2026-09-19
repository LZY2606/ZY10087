package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;

import java.util.*;

public final class RuntimeState {
    private final Topology topology;
    private final Map<String, RouteStatus> routeStatuses = new HashMap<>();
    private final Map<String, RouteStatus> previousStatuses = new HashMap<>();
    private final Map<String, Set<String>> sectionLockers = new HashMap<>();
    private final Map<String, Set<String>> sectionOccupants = new HashMap<>();
    private final Map<String, Set<String>> switchLockers = new HashMap<>();
    private final Map<String, SwitchPosition> switchPositions = new HashMap<>();
    private final Map<String, TreeSet<String>> releasedSections = new HashMap<>();
    private final Set<String> faultySections = new HashSet<>();
    private final Set<String> faultySwitches = new HashSet<>();
    private final Set<String> faultySignals = new HashSet<>();

    public RuntimeState(Topology topology) {
        this.topology = topology;
        for (RouteDefinition route : topology.routes()) {
            routeStatuses.put(route.id(), RouteStatus.IDLE);
            previousStatuses.put(route.id(), RouteStatus.IDLE);
            releasedSections.put(route.id(), new TreeSet<>());
        }
        for (SwitchDevice sw : topology.switches()) {
            switchPositions.put(sw.id(), SwitchPosition.NORMAL);
        }
        for (Section section : topology.sections()) {
            sectionLockers.put(section.id(), new HashSet<>());
            sectionOccupants.put(section.id(), new HashSet<>());
        }
        for (SwitchDevice sw : topology.switches()) {
            switchLockers.put(sw.id(), new HashSet<>());
        }
    }

    private RuntimeState(RuntimeState other) {
        this.topology = other.topology;
        copyMap(this.routeStatuses, other.routeStatuses);
        copyMap(this.previousStatuses, other.previousStatuses);
        copyNestedMap(this.sectionLockers, other.sectionLockers);
        copyNestedMap(this.sectionOccupants, other.sectionOccupants);
        copyNestedMap(this.switchLockers, other.switchLockers);
        copyMap(this.switchPositions, other.switchPositions);
        for (RouteDefinition route : topology.routes()) {
            releasedSections.put(route.id(), new TreeSet<>(other.releasedSections
                    .getOrDefault(route.id(), new TreeSet<>())));
        }
        this.faultySections.addAll(other.faultySections);
        this.faultySwitches.addAll(other.faultySwitches);
        this.faultySignals.addAll(other.faultySignals);
    }

    private <K, V> void copyMap(Map<K, V> target, Map<K, V> source) {
        source.forEach(target::put);
    }

    private void copyNestedMap(Map<String, Set<String>> target, Map<String, Set<String>> source) {
        source.forEach((key, value) -> target.put(key, new HashSet<>(value)));
    }

    public RuntimeState copy() {
        return new RuntimeState(this);
    }

    public Topology topology() {
        return topology;
    }

    public RouteStatus routeStatus(String routeId) {
        return routeStatuses.get(routeId);
    }

    public RouteStatus previousStatus(String routeId) {
        return previousStatuses.get(routeId);
    }

    public void routeStatus(String routeId, RouteStatus status) {
        routeStatuses.put(routeId, status);
    }

    public void previousStatus(String routeId, RouteStatus status) {
        previousStatuses.put(routeId, status);
    }

    public Set<String> lockers(String sectionId) {
        return sectionLockers.computeIfAbsent(sectionId, ignored -> new HashSet<>());
    }

    public Set<String> occupants(String sectionId) {
        return sectionOccupants.computeIfAbsent(sectionId, ignored -> new HashSet<>());
    }

    public Set<String> switchLockers(String switchId) {
        return switchLockers.computeIfAbsent(switchId, ignored -> new HashSet<>());
    }

    public SwitchPosition switchPosition(String switchId) {
        return switchPositions.getOrDefault(switchId, SwitchPosition.NORMAL);
    }

    public void switchPosition(String switchId, SwitchPosition position) {
        switchPositions.put(switchId, position);
    }

    public TreeSet<String> released(String routeId) {
        return releasedSections.computeIfAbsent(routeId, ignored -> new TreeSet<>());
    }

    public Set<String> faultySections() {
        return faultySections;
    }

    public Set<String> faultySwitches() {
        return faultySwitches;
    }

    public Set<String> faultySignals() {
        return faultySignals;
    }

    public Map<String, RouteStatus> routeStatuses() {
        return Collections.unmodifiableMap(routeStatuses);
    }

    public Map<String, Set<String>> sectionLockers() {
        return unmodifiableNested(sectionLockers);
    }

    public Map<String, Set<String>> sectionOccupants() {
        return unmodifiableNested(sectionOccupants);
    }

    public Map<String, Set<String>> switchLockers() {
        return unmodifiableNested(switchLockers);
    }

    public Map<String, SwitchPosition> switchPositions() {
        return Collections.unmodifiableMap(switchPositions);
    }

    public Map<String, TreeSet<String>> releasedSections() {
        return Collections.unmodifiableMap(releasedSections);
    }

    private Map<String, Set<String>> unmodifiableNested(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }
}
