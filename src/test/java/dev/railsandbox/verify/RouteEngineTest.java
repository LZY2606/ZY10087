package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteEngineTest {
    private Topology topology() {
        Section t1 = new Section("T1", List.of("IN", "OUT"));
        Section t2 = new Section("T2", List.of("IN", "OUT"));
        Section t3 = new Section("T3", List.of("IN", "OUT"));
        SwitchDevice sw = new SwitchDevice("P1", "C", List.of("N", "R"));
        SignalDevice signal = new SignalDevice("S1", "T1");
        return new Topology("t", List.of(t1, t2, t3), List.of(sw), List.of(signal),
                List.of(
                        new TrackLink("L1", new EndpointRef("T1", "OUT"), new EndpointRef("P1", "C"), LinkDirection.BOTH),
                        new TrackLink("L2", new EndpointRef("P1", "N"), new EndpointRef("T2", "IN"), LinkDirection.BOTH),
                        new TrackLink("L3", new EndpointRef("P1", "R"), new EndpointRef("T3", "IN"), LinkDirection.BOTH)
                ),
                List.of(new RouteDefinition("R1", "S1", List.of("T1", "T2"),
                        List.of(new RequiredSwitch("P1", SwitchPosition.NORMAL)), List.of())));
    }

    private ScenarioAction action(ActionType type, String route, String section, String device) {
        return new ScenarioAction("a", type, route, section, device);
    }

    @Test
    void requestOccupyOrderedReleaseAndComplete() {
        Topology topology = topology();
        RuleSet rules = RuleSet.safe("safe", "safe");
        RouteEngine engine = new RouteEngine(topology, rules);
        RuntimeState state = new RuntimeState(topology);

        assertTrue(engine.apply(state, action(ActionType.REQUEST_ROUTE, "R1", null, null)).accepted());
        assertEquals(RouteStatus.RESERVED, state.routeStatus("R1"));
        assertTrue(engine.apply(state, action(ActionType.OCCUPY_SECTION, "R1", "T2", null)).accepted() == false);
        assertTrue(engine.apply(state, action(ActionType.OCCUPY_SECTION, "R1", "T1", null)).accepted());
        assertEquals(RouteStatus.ACTIVE, state.routeStatus("R1"));
        assertTrue(engine.apply(state, action(ActionType.RELEASE_SECTION, "R1", "T2", null)).accepted() == false);
        assertTrue(engine.apply(state, action(ActionType.RELEASE_SECTION, "R1", "T1", null)).accepted());
        assertTrue(engine.apply(state, action(ActionType.OCCUPY_SECTION, "R1", "T2", null)).accepted());
        assertTrue(engine.apply(state, action(ActionType.RELEASE_SECTION, "R1", "T2", null)).accepted());
        assertEquals(RouteStatus.RELEASED, state.routeStatus("R1"));
    }

    @Test
    void faultDuringOccupiedRouteEntersFaultedAndRepairReturnsActive() {
        Topology topology = topology();
        RouteEngine engine = new RouteEngine(topology, RuleSet.safe("safe", "safe"));
        RuntimeState state = new RuntimeState(topology);
        engine.apply(state, action(ActionType.REQUEST_ROUTE, "R1", null, null));
        engine.apply(state, action(ActionType.OCCUPY_SECTION, "R1", "T1", null));

        TransitionResult fault = engine.apply(state, action(ActionType.DEVICE_FAULT, null, null, "T2"));
        assertTrue(fault.accepted());
        assertEquals(RouteStatus.FAULTED, state.routeStatus("R1"));
        assertTrue(new InvariantChecker(topology, RuleSet.safe("safe", "safe")).check(state).isEmpty());

        TransitionResult repair = engine.apply(state, action(ActionType.DEVICE_REPAIR, null, null, "T2"));
        assertTrue(repair.accepted());
        assertEquals(RouteStatus.ACTIVE, state.routeStatus("R1"));
    }

    @Test
    void faultBeforeEntryReleasesReservation() {
        Topology topology = topology();
        RouteEngine engine = new RouteEngine(topology, RuleSet.safe("safe", "safe"));
        RuntimeState state = new RuntimeState(topology);
        engine.apply(state, action(ActionType.REQUEST_ROUTE, "R1", null, null));
        engine.apply(state, action(ActionType.DEVICE_FAULT, null, null, "T2"));
        assertEquals(RouteStatus.IDLE, state.routeStatus("R1"));
        assertTrue(state.lockers("T2").isEmpty());
    }
}
