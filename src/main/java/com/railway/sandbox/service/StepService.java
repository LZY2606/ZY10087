package com.railway.sandbox.service;

import com.railway.sandbox.domain.Machine;
import com.railway.sandbox.domain.TransitionResult;
import com.railway.sandbox.model.RuleSet;
import com.railway.sandbox.model.RuntimeState;
import com.railway.sandbox.model.Scenario;
import com.railway.sandbox.model.Topology;
import com.railway.sandbox.verify.JsonIO;
import com.railway.sandbox.verify.VerifyReport;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Interactive time-stepping used by the UI: start from a scenario's initial
 * state, fire one operation at a time, observe trigger/effect/violations.
 * State is carried in the request (stateless service) so stepping survives
 * restarts and matches the verifier semantics exactly.
 */
@Service
public class StepService {

    private final SandboxService sandbox;

    public StepService(SandboxService sandbox) {
        this.sandbox = sandbox;
    }

    public Map<String, Object> initial(String topologyId, String ruleSetId, String scenarioId) {
        Machine machine = machine(topologyId, ruleSetId);
        Scenario sc = sandbox.readScenario(sandbox.scenarioStore().findById(scenarioId)
                .orElseThrow(() -> new NotFoundException("场景不存在: " + scenarioId)));
        RuntimeState state = machine.initialState(sc);
        return response(state, null, machine);
    }

    public record StepRequest(String topologyId, String ruleSetId, RuntimeState state,
                              Scenario.Operation operation) {}

    public Map<String, Object> step(StepRequest req) {
        Machine machine = machine(req.topologyId(), req.ruleSetId());
        RuntimeState before = req.state() == null ? new RuntimeState() : req.state();
        TransitionResult result = machine.fire(before, req.operation());
        Map<String, Object> out = response(result.accepted ? result.state : before, result, machine);
        out.put("trigger", result.trigger);
        out.put("effect", result.effect);
        out.put("accepted", result.accepted);
        out.put("rejectReason", result.rejectReason);
        out.put("violations", result.violations);
        return out;
    }

    private Machine machine(String topologyId, String ruleSetId) {
        Topology t = sandbox.readTopology(sandbox.requireTopology(topologyId));
        RuleSet r = sandbox.readRules(sandbox.ruleSetStore().findById(ruleSetId)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + ruleSetId)));
        return new Machine(t, r);
    }

    private Map<String, Object> response(RuntimeState state, TransitionResult result, Machine machine) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        out.put("invariants", machine.invariants(state));
        return out;
    }
}
