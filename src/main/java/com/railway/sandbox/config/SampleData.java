package com.railway.sandbox.config;

import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;

import java.util.List;
import java.util.Map;

/** Static demo content: one point, two tracks, two entry routes. */
public final class SampleData {

    private SampleData() {}

    public static Topology yard() {
        Topology t = new Topology();
        // points: b0 boundary entry; switch SW1 ports p1 (plus/entry), p2 (straight), p3 (minus)
        t.points.add(new Topology.Point("b0", true, 60.0, 200.0));
        t.points.add(new Topology.Point("p1", false, 160.0, 200.0));
        t.points.add(new Topology.Point("p2", false, 320.0, 180.0));
        t.points.add(new Topology.Point("p3", false, 320.0, 280.0));
        t.points.add(new Topology.Point("b2", true, 560.0, 180.0));
        t.points.add(new Topology.Point("b3", true, 560.0, 280.0));

        t.switches.add(new Topology.Switch("SW1", "p1", "p2", "p3"));

        t.links.add(new Topology.Link("l0", "b0", "p1"));
        t.sections.add(new Topology.Section("S0", "p1", "p2"));
        t.sections.add(new Topology.Section("S1", "p1", "p3"));
        t.sections.add(new Topology.Section("S2", "p2", "b2"));
        t.sections.add(new Topology.Section("S3", "p3", "b3"));
        // siding sharing the straight side beyond the switch (flank exposure)
        t.points.add(new Topology.Point("p4", false, 320.0, 80.0));
        t.points.add(new Topology.Point("b4", true, 470.0, 80.0));
        t.sections.add(new Topology.Section("S4", "p2", "p4"));
        t.sections.add(new Topology.Section("S5", "p4", "b4"));

        t.signals.add(new Topology.SignalDef("SigR1", "S0", "p1", "A_TO_B"));
        t.signals.add(new Topology.SignalDef("SigR2", "S1", "p1", "A_TO_B"));
        // shunt route shares S0 with R1: the mutex hazard demonstrated by the race scenario
        t.signals.add(new Topology.SignalDef("SigR3", "S0", "b0", "B_TO_A"));

        Topology.RouteDef r1 = new Topology.RouteDef("R1", "正线接车",
                "SigR1", List.of("S0", "S2"), Map.of("SW1", Topology.SwitchPosition.PLUS));
        Topology.RouteDef r2 = new Topology.RouteDef("R2", "侧向接车",
                "SigR2", List.of("S1", "S3"), Map.of("SW1", Topology.SwitchPosition.MINUS));
        Topology.RouteDef r3 = new Topology.RouteDef("R3", "调车折返",
                "SigR3", List.of("S0"), Map.of("SW1", Topology.SwitchPosition.PLUS));
        t.routes.add(r1);
        t.routes.add(r2);
        t.routes.add(r3);
        t.meta.put("layout", "b0-p1 SW1: plus->p2(S0,S2,b2), minus->p3(S1,S3,b3), siding S4,S5 off p2");
        return t;
    }

    /** One actor establishes, drives through and fully releases R1. Safe. */
    public static Scenario safeScenario() {
        Scenario sc = new Scenario();
        sc.name = "safe-r1";
        sc.description = "单列车：锁闭 R1 -> 开放信号 -> 进入/出清 -> 逐段释放";
        sc.switchPositions.put("SW1", Topology.SwitchPosition.PLUS);
        Scenario.Actor a = new Scenario.Actor("TRAIN-A");
        a.ops.add(new Scenario.Operation("REQUEST_ROUTE", "R1"));
        a.ops.add(new Scenario.Operation("CLEAR_SIGNAL", "SigR1"));
        a.ops.add(new Scenario.Operation("ENTER_SECTION", "S0"));
        a.ops.add(new Scenario.Operation("RELEASE_SECTION_NEXT", "R1"));
        a.ops.add(new Scenario.Operation("ENTER_SECTION", "S2"));
        a.ops.add(new Scenario.Operation("RELEASE_SECTION_NEXT", "R1"));
        a.ops.add(new Scenario.Operation("FINISH_ROUTE", "R1"));
        sc.actors.add(a);
        return sc;
    }

    /**
     * Two dispatchers race for the conflicting routes. Under the complete
     * rule set the second lock attempt is rejected (safe); under a relaxed
     * set dropping mutex both routes can lock and violate I1.
     */
    public static Scenario conflictScenario() {
        Scenario sc = new Scenario();
        sc.name = "race-r1-r3";
        sc.description = "两个调度员竞争共享 S0 的 R1 与 R3";
        sc.switchPositions.put("SW1", Topology.SwitchPosition.PLUS);
        Scenario.Actor a = new Scenario.Actor("DISPATCH-1");
        a.ops.add(new Scenario.Operation("REQUEST_ROUTE", "R1"));
        a.ops.add(new Scenario.Operation("CLEAR_SIGNAL", "SigR1"));
        a.ops.add(new Scenario.Operation("ENTER_SECTION", "S0"));
        Scenario.Actor b = new Scenario.Actor("DISPATCH-2");
        b.ops.add(new Scenario.Operation("REQUEST_ROUTE", "R3"));
        b.ops.add(new Scenario.Operation("CLEAR_SIGNAL", "SigR3"));
        b.ops.add(new Scenario.Operation("ENTER_SECTION", "S0"));
        sc.actors.add(a);
        sc.actors.add(b);
        return sc;
    }
}
