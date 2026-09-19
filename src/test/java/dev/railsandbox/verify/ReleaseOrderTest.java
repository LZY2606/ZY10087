package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseOrderTest {
    private Topology topology() {
        return new Topology("t",
                List.of(new Section("A", List.of("IN", "OUT")), new Section("B", List.of("IN", "OUT"))),
                List.of(),
                List.of(new SignalDevice("S", "A")),
                List.of(new TrackLink("L", new EndpointRef("A", "OUT"), new EndpointRef("B", "IN"), LinkDirection.BOTH)),
                List.of(new RouteDefinition("R", "S", List.of("A", "B"), List.of(), List.of())));
    }

    private ScenarioAction action(String id, ActionType type, String section) {
        return new ScenarioAction(id, type, "R", section, null);
    }

    @Test
    void weakReleaseAllowsInvalidSequenceThatInvariantDetects() {
        Scenario scenario = new Scenario("s", "release", List.of(new ScenarioProcess("p", List.of(
                action("r", ActionType.REQUEST_ROUTE, null),
                action("o1", ActionType.OCCUPY_SECTION, "A"),
                action("o2", ActionType.OCCUPY_SECTION, "B"),
                action("x2", ActionType.RELEASE_SECTION, "B"),
                action("x1", ActionType.RELEASE_SECTION, "A")
        ))));
        VerificationModels.VerificationReport report = new Verifier(topology(), RuleSet.weakRelease("w", "w"))
                .verify(scenario, new VerificationModels.SearchBounds(20, 100));
        assertEquals(VerificationModels.VerificationConclusion.COUNTEREXAMPLE, report.conclusion());
        assertEquals("释放顺序", report.counterexample().get(3).violations().get(0).invariant());
    }
}
