package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SandboxVerificationTest {
    private static Topology topology() {
        return new Topology(
                "t",
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
                List.of(new SignalDevice("S1", "T1"), new SignalDevice("S2", "T2"),
                        new SignalDevice("S3", "T4"), new SignalDevice("S4", "F2")),
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
                        new RouteDefinition("R2", "S2", List.of("T2", "T5"), List.of(),
                                List.of(new RequiredSwitch("P1", SwitchPosition.REVERSE), new RequiredSwitch("P2", SwitchPosition.REVERSE))),
                        new RouteDefinition("R3", "S3", List.of("T4", "T3"),
                                List.of(new RequiredSwitch("P2", SwitchPosition.NORMAL)),
                                List.of(new RequiredSwitch("P1", SwitchPosition.NORMAL)))
                )
        );
    }

    private static ScenarioAction action(String id, ActionType type, String route, String section) {
        return new ScenarioAction(id, type, route, section, null);
    }

    private static Scenario scenario() {
        return new Scenario("s", "test", List.of(
                new ScenarioProcess("train-a", List.of(
                        action("a1", ActionType.REQUEST_ROUTE, "R3", null),
                        action("a2", ActionType.OCCUPY_SECTION, "R3", "T4"),
                        action("a3", ActionType.OCCUPY_SECTION, "R3", "T3")
                )),
                new ScenarioProcess("train-b", List.of(
                        action("b1", ActionType.REQUEST_ROUTE, "R2", null),
                        action("b2", ActionType.OCCUPY_SECTION, "R2", "T2"),
                        action("b3", ActionType.OCCUPY_SECTION, "R2", "T5")
                ))
        ));
    }

    @Test
    void safeRulesRejectConflictingFlankRequestAndFinishSafe() {
        VerificationModels.VerificationReport report = new Verifier(topology(), RuleSet.safe("safe", "safe"))
                .verify(scenario(), new VerificationModels.SearchBounds(64, 50_000));
        assertEquals(VerificationModels.VerificationConclusion.SAFE, report.conclusion());
        assertTrue(report.rejectedTransitions().stream().anyMatch(item -> item.code().equals("FLANK_GUARD_POSITION")));
    }

    @Test
    void weakFlankRuleProducesShortReplayableCounterexampleWithGuards() {
        VerificationModels.VerificationReport report = new Verifier(topology(), RuleSet.weakFlank("weak", "weak"))
                .verify(scenario(), new VerificationModels.SearchBounds(64, 50_000));
        assertEquals(VerificationModels.VerificationConclusion.COUNTEREXAMPLE, report.conclusion());
        assertEquals(2, report.counterexampleLength());
        assertEquals("侧向防护", report.counterexample().get(1).violations().get(0).invariant());
        assertTrue(report.counterexample().get(1).guards().stream().anyMatch(GuardCheck::satisfied));
        assertNotNull(report.counterexample().get(1).after());
    }

    @Test
    void detectsDanglingDirectionAndUnreachableProblems() {
        Topology bad = new Topology("bad",
                List.of(new Section("A", List.of("P")), new Section("B", List.of("P")), new Section("C", List.of("P"))),
                List.of(new SwitchDevice("SW", "C", List.of("N", "R"))),
                List.of(new SignalDevice("S", "A")),
                List.of(new TrackLink("L", new EndpointRef("A", "P"), new EndpointRef("SW", "N"), LinkDirection.FORWARD)),
                List.of());
        List<Diagnostic> issues = new TopologyValidator(bad).validate();
        assertTrue(issues.stream().anyMatch(item -> item.code().equals("DANGLING_PORT")));
        assertTrue(issues.stream().anyMatch(item -> item.code().equals("UNREACHABLE_SECTION")));
    }

    @Test
    void boundIsNotSafety() {
        VerificationModels.VerificationReport report = new Verifier(topology(), RuleSet.safe("safe", "safe"))
                .verify(scenario(), new VerificationModels.SearchBounds(1, 2));
        assertEquals(VerificationModels.VerificationConclusion.BOUND_REACHED, report.conclusion());
    }
}
