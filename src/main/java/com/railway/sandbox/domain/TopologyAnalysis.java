package com.railway.sandbox.domain;

import com.railway.sandbox.model.Topology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Graph helpers and structural validation for imported topologies.
 *
 * Checks produced:
 *  - DANGLING        : an edge/route references a point or section that does not exist
 *  - DUPLICATE       : duplicated ids
 *  - BAD_PORT        : a switch references the same point twice or an unknown point
 *  - DIRECTION       : route section sequence is not adjacent on the track graph
 *  - SWITCH_MISMATCH : route switch map disagrees with the geometric positions
 *  - UNREACHABLE     : a section is not reachable from any boundary point
 */
public final class TopologyAnalysis {

    public static final class Issue {
        public final String code;
        public final String message;
        public final String ref;

        public Issue(String code, String message, String ref) {
            this.code = code;
            this.message = message;
            this.ref = ref;
        }
    }

    private final Topology t;
    private final Map<String, Set<String>> adj = new HashMap<>();
    private final Set<String> pointIds = new HashSet<>();
    private final Set<String> boundary = new HashSet<>();

    public TopologyAnalysis(Topology topology) {
        this.t = topology;
        for (Topology.Point p : t.points) {
            pointIds.add(p.id());
            if (p.boundary()) boundary.add(p.id());
        }
        addEdges();
    }

    private void connect(String a, String b) {
        adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    private void addEdges() {
        for (Topology.Section s : t.sections) {
            if (known(s.endA()) && known(s.endB())) connect(s.endA(), s.endB());
        }
        for (Topology.Link l : t.links) {
            if (known(l.endA()) && known(l.endB())) connect(l.endA(), l.endB());
        }
        for (Topology.Switch sw : t.switches) {
            if (known(sw.plus()) && known(sw.straight())) connect(sw.plus(), sw.straight());
            if (known(sw.plus()) && known(sw.minus())) connect(sw.plus(), sw.minus());
        }
    }

    public boolean known(String pointId) {
        return pointIds.contains(pointId);
    }

    public List<Issue> validate() {
        List<Issue> issues = new ArrayList<>();
        Set<String> seenPoints = new HashSet<>();
        for (Topology.Point p : t.points) {
            if (!seenPoints.add(p.id())) issues.add(new Issue("DUPLICATE", "重复节点 id", p.id()));
        }
        Set<String> seenSections = new HashSet<>();
        for (Topology.Section s : t.sections) {
            if (!seenSections.add(s.id())) issues.add(new Issue("DUPLICATE", "重复区段 id", s.id()));
            if (!known(s.endA())) issues.add(new Issue("DANGLING", "区段端点节点不存在", s.id() + ":" + s.endA()));
            if (!known(s.endB())) issues.add(new Issue("DANGLING", "区段端点节点不存在", s.id() + ":" + s.endB()));
        }
        for (Topology.Link l : t.links) {
            if (!known(l.endA()) || !known(l.endB()))
                issues.add(new Issue("DANGLING", "连接线端点节点不存在", l.id()));
        }
        for (Topology.Switch sw : t.switches) {
            Set<String> ports = new HashSet<>();
            ports.add(sw.plus());
            ports.add(sw.straight());
            ports.add(sw.minus());
            if (ports.size() < 3) issues.add(new Issue("BAD_PORT", "道岔三个端口必须互不相同", sw.id()));
            for (String p : ports) {
                if (!known(p)) issues.add(new Issue("BAD_PORT", "道岔端口节点不存在", sw.id() + ":" + p));
            }
        }
        for (Topology.SignalDef sig : t.signals) {
            if (section(sig.section()) == null)
                issues.add(new Issue("DANGLING", "信号机所属区段不存在", sig.id()));
            if (!known(sig.atPoint()))
                issues.add(new Issue("DANGLING", "信号机安装节点不存在", sig.id() + ":" + sig.atPoint()));
            if (!"A_TO_B".equals(sig.direction()) && !"B_TO_A".equals(sig.direction()))
                issues.add(new Issue("BAD_DIRECTION", "信号方向必须是 A_TO_B 或 B_TO_A", sig.id()));
        }
        validateRoutes(issues);
        findUnreachable(issues);
        issues.sort(Comparator.comparing((Issue i) -> i.code).thenComparing(i -> i.ref));
        return issues;
    }

    public Topology.Section section(String id) {
        for (Topology.Section s : t.sections) if (s.id().equals(id)) return s;
        return null;
    }

    public Topology.Switch switchDef(String id) {
        for (Topology.Switch sw : t.switches) if (sw.id().equals(id)) return sw;
        return null;
    }

    /**
     * Walk a route as one continuous path, trying both orientations of the
     * first section. Returns the sequence of ports walked (size n+1) or null.
     */
    public List<String> routeWalk(Topology.RouteDef r) {
        if (r.sections().isEmpty()) return List.of();
        Topology.Section first = section(r.sections().get(0));
        if (first == null) return null;
        for (String entry : new String[]{first.endB(), first.endA()}) {
            List<String> ports = new ArrayList<>();
            String enter = entry;
            boolean ok = true;
            for (String sid : r.sections()) {
                Topology.Section sec = section(sid);
                if (sec == null) { ok = false; break; }
                if (!enter.equals(sec.endA()) && !enter.equals(sec.endB())) { ok = false; break; }
                ports.add(enter);
                enter = enter.equals(sec.endA()) ? sec.endB() : sec.endA();
            }
            if (ok) {
                ports.add(enter);
                return ports;
            }
        }
        return null;
    }

    /** Switch transitions used by a route walk, keyed by switch id. */
    public Map<String, Topology.SwitchPosition> geometricSwitchRequirements(Topology.RouteDef r) {
        Map<String, Topology.SwitchPosition> required = new LinkedHashMap<>();
        List<String> ports = routeWalk(r);
        if (ports == null) return required;
        for (int i = 0; i + 1 < ports.size(); i++) {
            String a = ports.get(i);
            String b = ports.get(i + 1);
            for (Topology.Switch sw : t.switches) {
                Topology.SwitchPosition pos = geometricPosition(sw, a, b);
                if (pos != null) required.put(sw.id(), pos);
            }
        }
        return required;
    }

    private void validateRoutes(List<Issue> issues) {
        Set<String> routeIds = new HashSet<>();
        for (Topology.RouteDef r : t.routes) {
            if (!routeIds.add(r.id())) issues.add(new Issue("DUPLICATE", "重复进路 id", r.id()));
            if (new HashSet<>(r.sections()).size() != r.sections().size())
                issues.add(new Issue("DIRECTION", "进路区段序列出现重复", r.id()));
            for (String sid : r.sections()) {
                if (section(sid) == null)
                    issues.add(new Issue("DANGLING", "进路引用不存在的区段", r.id() + ":" + sid));
            }
            for (String swId : r.switches().keySet()) {
                if (switchDef(swId) == null)
                    issues.add(new Issue("DANGLING", "进路引用不存在的道岔", r.id() + ":" + swId));
            }
            if (r.sections().stream().anyMatch(s -> section(s) == null)) continue;
            List<String> ports = routeWalk(r);
            if (ports == null) {
                issues.add(new Issue("DIRECTION",
                        "方向不一致: 区段序列不能铺成一条连续路径", r.id()));
                continue;
            }
            Map<String, Topology.SwitchPosition> geometric = geometricSwitchRequirements(r);
            for (Map.Entry<String, Topology.SwitchPosition> e : geometric.entrySet()) {
                Topology.SwitchPosition declared = r.switches().get(e.getKey());
                if (declared == null) {
                    issues.add(new Issue("SWITCH_MISMATCH",
                            "进路缺少道岔 " + e.getKey() + " 的位置要求（几何走向要求 "
                                    + e.getValue() + "）", r.id()));
                } else if (declared != e.getValue()) {
                    issues.add(new Issue("SWITCH_MISMATCH",
                            "道岔 " + e.getKey() + " 声明位置 " + declared
                                    + " 与进路几何走向 " + e.getValue() + " 不一致", r.id()));
                }
            }
            for (String swId : r.switches().keySet()) {
                if (!geometric.containsKey(swId) && switchDef(swId) != null)
                    issues.add(new Issue("SWITCH_MISMATCH",
                            "道岔 " + swId + " 不在进路几何路径上", r.id() + ":" + swId));
            }
        }
    }

    private Topology.SwitchPosition geometricPosition(Topology.Switch sw, String from, String to) {
        boolean plusStraight = (from.equals(sw.plus()) && to.equals(sw.straight()))
                || (to.equals(sw.plus()) && from.equals(sw.straight()));
        boolean plusMinus = (from.equals(sw.plus()) && to.equals(sw.minus()))
                || (to.equals(sw.plus()) && from.equals(sw.minus()));
        if (plusStraight) return Topology.SwitchPosition.PLUS;
        if (plusMinus) return Topology.SwitchPosition.MINUS;
        return null;
    }

    private void findUnreachable(List<Issue> issues) {
        Set<String> reached = new HashSet<>();
        for (String b : boundary) dfs(b, reached);
        for (Topology.Section s : t.sections) {
            if (!reached.contains(s.endA()) && !reached.contains(s.endB()))
                issues.add(new Issue("UNREACHABLE",
                        "区段无法从任何边界节点到达（孤立子图）", s.id()));
        }
    }

    private void dfs(String start, Set<String> reached) {
        if (start == null || !reached.add(start)) return;
        for (String n : adj.getOrDefault(start, Set.of())) dfs(n, reached);
    }

    /**
     * Flank-protection resources: for every diverging switch the route uses,
     * the sections connected to the unused side port are counted as lateral
     * neighbours and must be free while the route is occupied.
     */
    public Set<String> flankSections(Topology.RouteDef r) {
        Set<String> out = new TreeSet<>();
        Map<String, Topology.SwitchPosition> used = geometricSwitchRequirements(r);
        for (Map.Entry<String, Topology.SwitchPosition> e : used.entrySet()) {
            if (e.getValue() != Topology.SwitchPosition.MINUS) continue;
            Topology.Switch sw = switchDef(e.getKey());
            if (sw == null) continue;
            for (Topology.Section s : t.sections) {
                boolean touchesStraight = s.endA().equals(sw.straight()) || s.endB().equals(sw.straight());
                boolean onRoute = r.sections().contains(s.id());
                if (touchesStraight && !onRoute) out.add(s.id());
            }
        }
        return out;
    }

    public Map<String, Object> summary() {
        Set<String> reached = new HashSet<>();
        for (String b : boundary) dfs(b, reached);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("points", t.points.size());
        m.put("switches", t.switches.size());
        m.put("sections", t.sections.size());
        m.put("signals", t.signals.size());
        m.put("routes", t.routes.size());
        m.put("boundaryPoints", new TreeSet<>(boundary));
        List<String> unreachable = new ArrayList<>();
        for (Topology.Section s : t.sections)
            if (!reached.contains(s.endA()) && !reached.contains(s.endB())) unreachable.add(s.id());
        m.put("unreachableSections", unreachable);
        m.put("issues", validate().size());
        return m;
    }
}
