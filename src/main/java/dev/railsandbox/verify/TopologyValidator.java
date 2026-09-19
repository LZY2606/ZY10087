package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;

import java.util.*;

public final class TopologyValidator {
    private final Topology topology;
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final Map<String, SwitchDevice> switches = new LinkedHashMap<>();
    private final Map<String, SignalDevice> signals = new LinkedHashMap<>();
    private final Set<String> endpointKeys = new HashSet<>();
    private final Set<String> usedEndpoints = new HashSet<>();
    private final Map<String, Set<String>> graph = new HashMap<>();

    public TopologyValidator(Topology topology) {
        this.topology = topology;
    }

    public List<Diagnostic> validate() {
        List<Diagnostic> issues = new ArrayList<>();
        indexDevices(issues);
        readLinks(issues);
        validateSwitchPorts(issues);
        validateRoutes(issues);
        validateReachableSections(issues);
        return issues;
    }

    public boolean valid() {
        return validate().stream().noneMatch(Diagnostic::error);
    }

    private void indexDevices(List<Diagnostic> issues) {
        for (Section section : topology.sections()) {
            if (sections.put(section.id(), section) != null) {
                issues.add(Diagnostic.error("DUPLICATE_SECTION", "区段标识重复：" + section.id()));
            }
            if (section.ports().isEmpty()) {
                issues.add(Diagnostic.error("SECTION_WITHOUT_PORT", "区段至少需要一个端口：" + section.id()));
            }
            for (String port : section.ports()) {
                addEndpoint(issues, section.id(), port, "区段端口重复");
            }
        }
        for (SwitchDevice sw : topology.switches()) {
            if (switches.put(sw.id(), sw) != null) {
                issues.add(Diagnostic.error("DUPLICATE_SWITCH", "道岔标识重复：" + sw.id()));
            }
            addEndpoint(issues, sw.id(), sw.commonPort(), "道岔端口重复");
            for (String port : sw.branchPorts()) {
                addEndpoint(issues, sw.id(), port, "道岔端口重复");
            }
        }
        for (SignalDevice signal : topology.signals()) {
            if (signals.put(signal.id(), signal) != null) {
                issues.add(Diagnostic.error("DUPLICATE_SIGNAL", "信号标识重复：" + signal.id()));
            }
            if (!sections.containsKey(signal.section())) {
                issues.add(Diagnostic.error("DANGLING_SIGNAL", "信号 " + signal.id() + " 引用不存在区段 " + signal.section()));
            }
        }
    }

    private void addEndpoint(List<Diagnostic> issues, String device, String port, String message) {
        String key = device + "#" + port;
        if (!endpointKeys.add(key)) {
            issues.add(Diagnostic.error("DUPLICATE_PORT", message + "：" + key));
        }
    }

    private void readLinks(List<Diagnostic> issues) {
        Set<String> linkIds = new HashSet<>();
        Map<String, String> portOwners = new HashMap<>();
        Set<String> undirectedPairs = new HashSet<>();
        for (TrackLink link : topology.links()) {
            if (!linkIds.add(link.id())) {
                issues.add(Diagnostic.error("DUPLICATE_LINK", "轨道连接标识重复：" + link.id()));
            }
            if (!endpointKeys.contains(link.from().key())) {
                issues.add(Diagnostic.error("DANGLING_LINK", "连接 " + link.id() + " 的起点悬空：" + link.from().key()));
            }
            if (!endpointKeys.contains(link.to().key())) {
                issues.add(Diagnostic.error("DANGLING_LINK", "连接 " + link.id() + " 的终点悬空：" + link.to().key()));
            }
            if (!endpointKeys.contains(link.from().key()) || !endpointKeys.contains(link.to().key())) {
                continue;
            }
            claimPort(issues, portOwners, link.id(), link.from().key());
            claimPort(issues, portOwners, link.id(), link.to().key());
            usedEndpoints.add(link.from().key());
            usedEndpoints.add(link.to().key());
            if (link.direction() != LinkDirection.REVERSE) {
                graph.computeIfAbsent(link.from().key(), ignored -> new HashSet()).add(link.to().key());
            }
            if (link.direction() != LinkDirection.FORWARD) {
                graph.computeIfAbsent(link.to().key(), ignored -> new HashSet()).add(link.from().key());
            }
            String pair = pairKey(link.from().key(), link.to().key());
            if (!undirectedPairs.add(pair)) {
                issues.add(Diagnostic.error("DIRECTION_CONFLICT", "连接 " + link.id() + " 与既有连接重复，方向声明无法同时成立"));
            }
        }
    }

    private void claimPort(List<Diagnostic> issues, Map<String, String> owners, String linkId, String endpoint) {
        String previous = owners.put(endpoint, linkId);
        if (previous != null) {
            issues.add(Diagnostic.error("DIRECTION_CONFLICT", "端口 " + endpoint + " 同时被连接 " + previous + " 与 " + linkId + " 占用，方向不一致"));
        }
    }

    private void validateSwitchPorts(List<Diagnostic> issues) {
        for (SwitchDevice sw : topology.switches()) {
            if (sw.branchPorts().size() != 2 || sw.branchPorts().contains(sw.commonPort())) {
                issues.add(Diagnostic.error("SWITCH_SHAPE", "道岔 " + sw.id() + " 必须有公共端口和两个不同分支端口"));
            }
            checkPortUsed(issues, sw.id(), sw.commonPort());
            for (String port : sw.branchPorts()) {
                checkPortUsed(issues, sw.id(), port);
            }
        }
    }

    private void checkPortUsed(List<Diagnostic> issues, String sw, String port) {
        if (!usedEndpoints.contains(sw + "#" + port)) {
            issues.add(Diagnostic.error("DANGLING_PORT", "道岔 " + sw + " 的端口未连接：" + port));
        }
    }

    private void validateRoutes(List<Diagnostic> issues) {
        Set<String> routeIds = new HashSet<>();
        for (RouteDefinition route : topology.routes()) {
            if (!routeIds.add(route.id())) {
                issues.add(Diagnostic.error("DUPLICATE_ROUTE", "进路标识重复：" + route.id()));
            }
            if (route.sections().isEmpty()) {
                issues.add(Diagnostic.error("EMPTY_ROUTE", "进路不能为空：" + route.id()));
                continue;
            }
            SignalDevice signal = signals.get(route.entrySignal());
            if (signal == null) {
                issues.add(Diagnostic.error("DANGLING_ROUTE_SIGNAL", "进路 " + route.id() + " 引用不存在信号 " + route.entrySignal()));
            } else if (!route.sections().get(0).equals(signal.section())) {
                issues.add(Diagnostic.error("ROUTE_SIGNAL_MISMATCH", "进路 " + route.id() + " 的入口信号必须位于首区段"));
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String sectionId : route.sections()) {
                if (!sections.containsKey(sectionId)) {
                    issues.add(Diagnostic.error("DANGLING_ROUTE_SECTION", "进路 " + route.id() + " 引用不存在区段 " + sectionId));
                } else if (!seen.add(sectionId)) {
                    issues.add(Diagnostic.error("ROUTE_LOOP", "进路 " + route.id() + " 重复经过区段 " + sectionId));
                }
            }
            for (int index = 0; index + 1 < route.sections().size(); index++) {
                String from = route.sections().get(index);
                String to = route.sections().get(index + 1);
                if (sections.containsKey(from) && sections.containsKey(to) && !sectionsConnected(from, to)) {
                    issues.add(Diagnostic.error("ROUTE_DIRECTION_MISMATCH", "进路 " + route.id() + " 中 " + from + " 到 " + to + " 没有与拓扑方向一致的直接跨越"));
                }
            }
            for (RequiredSwitch required : route.explicitSwitches()) {
                SwitchDevice sw = switches.get(required.switchId());
                if (sw == null) {
                    issues.add(Diagnostic.error("DANGLING_ROUTE_SWITCH", "进路 " + route.id() + " 引用不存在道岔 " + required.switchId()));
                    continue;
                }
                String requiredPort = required.position() == SwitchPosition.NORMAL ? portAt(sw, 0) : portAt(sw, 1);
                if (!sw.branchPorts().contains(requiredPort)) {
                    issues.add(Diagnostic.error("ROUTE_SWITCH_PORT", "进路 " + route.id() + " 请求的道岔端口不存在：" + required.switchId()));
                }
            }
            for (RequiredSwitch guard : route.explicitFlankGuards()) {
                if (!switches.containsKey(guard.switchId())) {
                    issues.add(Diagnostic.error("DANGLING_FLANK_GUARD", "进路 " + route.id() + " 引用不存在侧向防护道岔 " + guard.switchId()));
                }
            }
        }
    }

    private boolean sectionsConnected(String source, String target) {
        for (String port : sections.get(source).ports()) {
            Deque<String> queue = new ArrayDeque<>(List.of(source + "#" + port));
            Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                String device = current.substring(0, current.indexOf('#'));
                if (device.equals(target)) {
                    return true;
                }
                if (sections.containsKey(device) && !device.equals(source)) {
                    continue;
                }
                SwitchDevice sw = switches.get(device);
                List<String> next = new ArrayList<>(graph.getOrDefault(current, Set.of()));
                if (sw != null) {
                    String portName = current.substring(current.indexOf('#') + 1);
                    for (String other : switchPorts(sw)) {
                        if (!other.equals(portName)) {
                            next.add(sw.id() + "#" + other);
                        }
                    }
                }
                Collections.sort(next);
                queue.addAll(next);
            }
        }
        return false;
    }

    private void validateReachableSections(List<Diagnostic> issues) {
        Set<String> reachable = new HashSet<>();
        for (SignalDevice signal : topology.signals()) {
            Deque<String> queue = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            reachable.add(signal.section());
            sections.getOrDefault(signal.section(), new Section(signal.section(), List.of())).ports()
                    .forEach(port -> queue.add(signal.section() + "#" + port));
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                String device = current.substring(0, current.indexOf('#'));
                if (sections.containsKey(device)) {
                    reachable.add(device);
                }
                SwitchDevice sw = switches.get(device);
                List<String> next = new ArrayList<>(graph.getOrDefault(current, Set.of()));
                if (sw != null) {
                    String portName = current.substring(current.indexOf('#') + 1);
                    for (String other : switchPorts(sw)) {
                        if (!other.equals(portName)) {
                            next.add(sw.id() + "#" + other);
                        }
                    }
                }
                for (String endpoint : next) {
                    String nextDevice = endpoint.substring(0, endpoint.indexOf('#'));
                    if (sections.containsKey(nextDevice)) {
                        reachable.add(nextDevice);
                    }
                    queue.add(endpoint);
                }
            }
        }
        for (Section section : topology.sections()) {
            if (!reachable.contains(section.id())) {
                issues.add(Diagnostic.error("UNREACHABLE_SECTION", "无法从任何入口信号到达区段 " + section.id()));
            }
        }
    }

    private List<String> switchPorts(SwitchDevice sw) {
        List<String> ports = new ArrayList<>();
        ports.add(sw.commonPort());
        ports.addAll(sw.branchPorts());
        return ports;
    }

    private String portAt(SwitchDevice sw, int index) {
        return sw.branchPorts().size() > index ? sw.branchPorts().get(index) : "__missing__";
    }

    private String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a;
    }
}
