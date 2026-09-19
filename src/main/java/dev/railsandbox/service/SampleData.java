package dev.railsandbox.service;

import dev.railsandbox.domain.Models.*;

import java.util.List;

final class SampleData {
    private SampleData() {
    }

    static Topology topology() {
        return new Topology(
                "station-a",
                List.of(
                        new Section("T1", List.of("IN", "OUT")),
                        new Section("T2", List.of("IN", "OUT")),
                        new Section("T3", List.of("IN", "OUT")),
                        new Section("T4", List.of("IN", "OUT")),
                        new Section("T5", List.of("IN", "OUT", "SIDE")),
                        new Section("F1", List.of("IN", "OUT")),
                        new Section("F2", List.of("IN", "OUT"))
                ),
                List.of(
                        new SwitchDevice("P1", "C", List.of("N", "R")),
                        new SwitchDevice("P2", "C", List.of("N", "R"))
                ),
                List.of(
                    new SignalDevice("S1", "T1"),
                    new SignalDevice("S2", "T2"),
                    new SignalDevice("S3", "T4"),
                    new SignalDevice("S4", "F2")
                ),
                List.of(
                        new TrackLink("L1", new EndpointRef("T1", "OUT"), new EndpointRef("P1", "C"), LinkDirection.BOTH),
                        new TrackLink("L2", new EndpointRef("P1", "N"), new EndpointRef("T3", "IN"), LinkDirection.BOTH),
                        new TrackLink("L3", new EndpointRef("T2", "OUT"), new EndpointRef("T5", "IN"), LinkDirection.BOTH),
                        new TrackLink("L4", new EndpointRef("F1", "OUT"), new EndpointRef("P1", "R"), LinkDirection.BOTH),
                        new TrackLink("L5", new EndpointRef("T4", "OUT"), new EndpointRef("P2", "C"), LinkDirection.BOTH),
                        new TrackLink("L6", new EndpointRef("P2", "N"), new EndpointRef("F2", "IN"), LinkDirection.BOTH),
                        new TrackLink("L7", new EndpointRef("P2", "R"), new EndpointRef("T5", "SIDE"), LinkDirection.BOTH),
                        new TrackLink("L8", new EndpointRef("T4", "IN"), new EndpointRef("T3", "OUT"), LinkDirection.BOTH)
                ),
                List.of(
                        new RouteDefinition("R1", "S1", List.of("T1", "T3"),
                                List.of(new RequiredSwitch("P1", SwitchPosition.NORMAL)),
                                List.of(new RequiredSwitch("P2", SwitchPosition.NORMAL))),
                        new RouteDefinition("R2", "S2", List.of("T2", "T5"),
                                List.of(),
                                List.of(new RequiredSwitch("P1", SwitchPosition.REVERSE), new RequiredSwitch("P2", SwitchPosition.REVERSE))),
                        new RouteDefinition("R3", "S3", List.of("T4", "T3"),
                                List.of(new RequiredSwitch("P2", SwitchPosition.NORMAL)),
                                List.of(new RequiredSwitch("P1", SwitchPosition.NORMAL)))
                )
        );
    }

    static RuleSet approvedRules() {
        return RuleSet.safe("safe-interlocking", "批准：完整联锁规则");
    }

    static RuleSet weakFlankRules() {
        return RuleSet.weakFlank("weak-flank", "候选：关闭侧向防护不变量");
    }

    static RuleSet weakReleaseRules() {
        return RuleSet.weakRelease("weak-release", "候选：关闭顺序释放不变量");
    }

    static Scenario scenario() {
        return new Scenario("crossing-trains", "两列接近列车与一条侧向防护进路的交错", List.of(
                new ScenarioProcess("train-a", List.of(
                        action("a1", ActionType.REQUEST_ROUTE, "R3", null, null),
                        action("a2", ActionType.OCCUPY_SECTION, "R3", "T4", null),
                        action("a3", ActionType.OCCUPY_SECTION, "R3", "T3", null)
                )),
                new ScenarioProcess("train-b", List.of(
                        action("b1", ActionType.REQUEST_ROUTE, "R2", null, null),
                        action("b2", ActionType.OCCUPY_SECTION, "R2", "T2", null),
                        action("b3", ActionType.OCCUPY_SECTION, "R2", "T5", null)
                ))
        ));
    }

    private static ScenarioAction action(String id, ActionType type, String route, String section, String device) {
        return new ScenarioAction(id, type, route, section, device);
    }
}
