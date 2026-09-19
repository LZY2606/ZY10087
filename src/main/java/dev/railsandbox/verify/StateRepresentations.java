package dev.railsandbox.verify;

import java.util.*;
import java.util.stream.Collectors;

public final class StateRepresentations {
    private StateRepresentations() {
    }

    public static String canonicalKey(RuntimeState state) {
        StringBuilder builder = new StringBuilder();
        appendMap(builder, "routes", state.routeStatuses().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name())));
        appendNested(builder, "occupants", state.sectionOccupants());
        appendNested(builder, "locks", state.sectionLockers());
        appendNested(builder, "switchLocks", state.switchLockers());
        appendMap(builder, "switchPositions", state.switchPositions().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name())));
        appendNested(builder, "released", state.releasedSections());
        appendList(builder, "faultSections", state.faultySections());
        appendList(builder, "faultSwitches", state.faultySwitches());
        appendList(builder, "faultSignals", state.faultySignals());
        return builder.toString();
    }

    public static VerificationModels.StateSnapshot snapshot(RuntimeState state) {
        return new VerificationModels.StateSnapshot(
                state.routeStatuses().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().name(), (a, b) -> a, TreeMap::new)),
                nested(state.sectionOccupants()),
                nested(state.sectionLockers()),
                nested(state.switchLockers()),
                state.switchPositions().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().name(), (a, b) -> a, TreeMap::new)),
                state.releasedSections().entrySet().stream()
                        .flatMap(entry -> entry.getValue().stream().map(section -> entry.getKey() + ":" + section))
                        .sorted().toList(),
                listFaults(state)
        );
    }

    private static Map<String, List<String>> nested(Map<String, ? extends Collection<String>> source) {
        Map<String, List<String>> result = new TreeMap<>();
        source.forEach((key, values) -> result.put(key, values.stream().sorted().toList()));
        return result;
    }

    private static List<String> listFaults(RuntimeState state) {
        List<String> result = new ArrayList<>();
        state.faultySections().stream().map(id -> "SECTION:" + id).forEach(result::add);
        state.faultySwitches().stream().map(id -> "SWITCH:" + id).forEach(result::add);
        state.faultySignals().stream().map(id -> "SIGNAL:" + id).forEach(result::add);
        Collections.sort(result);
        return result;
    }

    private static <T> void appendMap(StringBuilder builder, String name, Map<String, T> map) {
        builder.append(name).append('{');
        new TreeMap<>(map).forEach((key, value) -> builder.append(key).append('=').append(value).append(';'));
        builder.append('}');
    }

    private static void appendNested(StringBuilder builder, String name, Map<String, ? extends Collection<String>> map) {
        builder.append(name).append('{');
        new TreeMap<>(map).forEach((key, values) -> builder.append(key).append('=')
                .append(values.stream().sorted().collect(Collectors.joining(","))).append(';'));
        builder.append('}');
    }

    private static void appendList(StringBuilder builder, String name, Collection<String> values) {
        builder.append(name).append('[').append(values.stream().sorted().collect(Collectors.joining(","))).append(']');
    }
}
