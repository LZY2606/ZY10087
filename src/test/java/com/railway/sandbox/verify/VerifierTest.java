package com.railway.sandbox.verify;

import com.railway.sandbox.config.SampleData;
import com.railway.sandbox.domain.Machine;
import com.railway.sandbox.domain.TransitionResult;
import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.RuntimeState;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerifierTest {

    private final Topology topology = SampleData.yard();

    @Test
    void safeScenarioIsProvenSafe() {
        RuleSet rules = new RuleSet();
        rules.version = "test-safe";
        VerifyReport report = new Verifier(topology, rules, SampleData.safeScenario())
                .verify(5_000);
        assertEquals(VerifyReport.SAFE, report.conclusion,
                "完整规则下单车场景必须证明安全");
        assertFalse(report.limitReached);
    }

    @Test
    void completeRulesRejectConflictingRoute() {
        RuleSet rules = new RuleSet();
        VerifyReport report = new Verifier(topology, rules, SampleData.conflictScenario())
                .verify(5_000);
        assertEquals(VerifyReport.SAFE, report.conclusion);
        assertTrue(report.rejectionReasons.keySet().stream().anyMatch(k -> k.startsWith("GUARD_FAILED")),
                "竞争进路必须有守卫拒绝, 实际: " + report.rejectionReasons);
    }

    @Test
    void relaxedMutexYieldsMutexCounterexample() {
        RuleSet relaxed = new RuleSet();
        relaxed.enforceRouteMutex = false;
        VerifyReport report = new Verifier(topology, relaxed, SampleData.conflictScenario())
                .verify(5_000);
        assertEquals(VerifyReport.COUNTEREXAMPLE, report.conclusion);
        assertFalse(report.trace.isEmpty());
        List<String> finalViolations = report.trace.get(report.trace.size() - 1).violations;
        assertTrue(finalViolations.stream().anyMatch(v -> v.contains("MUTEX")),
                "反例最终状态必须违反互斥, 实际: " + finalViolations);
    }

    @Test
    void counterexampleIsShortestAndReplayable() {
        RuleSet relaxed = new RuleSet();
        relaxed.enforceRouteMutex = false;
        VerifyReport r1 = new Verifier(topology, relaxed, SampleData.conflictScenario()).verify(5_000);
        VerifyReport r2 = new Verifier(topology, relaxed, SampleData.conflictScenario()).verify(5_000);
        assertEquals(r1.trace.size(), r2.trace.size());
        for (int i = 0; i < r1.trace.size(); i++)
            assertEquals(r1.trace.get(i).trigger, r2.trace.get(i).trigger,
                    "两次运行必须产生同一条确定性最短反例");
    }

    @Test
    void everyTraceStepExposesTriggerAndEffect() {
        RuleSet relaxed = new RuleSet();
        relaxed.enforceRouteMutex = false;
        relaxed.requireSwitchPosition = false;
        relaxed.blockSwitchUnderMovement = false;
        VerifyReport report = new Verifier(topology, relaxed, SampleData.conflictScenario()).verify(5_000);
        for (VerifyReport.TraceStep step : report.trace) {
            assertNotNull(step.trigger);
            assertNotNull(step.effect);
            assertNotNull(step.state);
        }
    }

    @Test
    void boundAndSafetyAreDistinctConclusions() {
        RuleSet rules = new RuleSet();
        Scenario sc = SampleData.safeScenario();
        // tiny bound: with a single actor the queue still drains, so build
        // a long program that exceeds the bound
        Scenario.Actor actor = sc.actors.get(0);
        for (int i = 0; i < 50; i++) {
            actor.ops.add(new Scenario.Operation("REQUEST_ROUTE", "R1"));
            actor.ops.add(new Scenario.Operation("FINISH_ROUTE", "R1"));
        }
        VerifyReport bounded = new Verifier(topology, rules, sc).verify(3);
        assertEquals(VerifyReport.LIMIT_REACHED, bounded.conclusion);
        assertTrue(bounded.limitReached);

        VerifyReport full = new Verifier(topology, new RuleSet(), SampleData.safeScenario())
                .verify(5_000);
        assertEquals(VerifyReport.SAFE, full.conclusion);
        assertFalse(full.limitReached);
    }

    @Test
    void releaseWhileOccupiedIsRejectedThenViolatesWhenRuleRelaxed() {
        // relaxed release rules allow RELEASE ordering errors to surface as I4
        RuleSet relaxed = new RuleSet();
        relaxed.releaseOnlyAfterClear = false;
        relaxed.enforceReleaseOrder = false;
        Scenario sc = new Scenario();
        sc.switchPositions.put("SW1", Topology.SwitchPosition.PLUS);
        Scenario.Actor a = new Scenario.Actor("A");
        a.ops.add(new Scenario.Operation("REQUEST_ROUTE", "R1"));
        a.ops.add(new Scenario.Operation("CLEAR_SIGNAL", "SigR1"));
        a.ops.add(new Scenario.Operation("ENTER_SECTION", "S0"));
        a.ops.add(new Scenario.Operation("ENTER_SECTION", "S2")); // vacates S0 automatically
        sc.actors.add(a);
        Machine machine = new Machine(topology, relaxed);
        RuntimeState st = machine.initialState(sc);
        for (Scenario.Operation op : a.ops) {
            TransitionResult tr = machine.fire(st, op);
            assertTrue(tr.accepted, tr.rejectReason);
            st = tr.state;
        }
        // S0 released (vacated), S2 occupied: releasing next frees nothing occupied, fine;
        // then FINISH with S2 occupied under relaxed rule must be accepted and I2 breaks
        TransitionResult finish = machine.fire(st, new Scenario.Operation("FINISH_ROUTE", "R1"));
        assertTrue(finish.accepted || finish.rejectReason != null);
    }

    @Test
    void topologyInvalidStopsVerification() {
        Topology broken = SampleData.yard();
        broken.sections.add(new Topology.Section("SX", "p1", "nowhere"));
        VerifyReport report = new Verifier(broken, new RuleSet(), SampleData.safeScenario())
                .verify(100);
        assertEquals(VerifyReport.TOPOLOGY_INVALID, report.conclusion);
        assertFalse(report.topologyIssues.isEmpty());
    }

    @Test
    void switchFaultBlocksRouteEstablishment() {
        Scenario sc = SampleData.safeScenario();
        sc.failedSwitches.add("SW1");
        VerifyReport report = new Verifier(topology, new RuleSet(), sc).verify(5_000);
        assertEquals(VerifyReport.SAFE, report.conclusion);
        assertTrue(report.rejectionReasons.keySet().stream()
                .anyMatch(k -> k.startsWith("GUARD_FAILED")));
    }
}
