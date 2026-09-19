package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.*;

import java.util.*;

public final class Verifier {
    private final Topology topology;
    private final RuleSet rules;

    public Verifier(Topology topology, RuleSet rules) {
        this.topology = topology;
        this.rules = rules;
    }

    public VerificationModels.VerificationReport verify(Scenario scenario, VerificationModels.SearchBounds bounds) {
        RouteEngine engine = new RouteEngine(topology, rules);
        InvariantChecker checker = new InvariantChecker(topology, rules);
        List<ScenarioProcess> processes = scenario.processes().stream()
                .sorted(Comparator.comparing(ScenarioProcess::id)).toList();
        int[] limits = processes.stream().mapToInt(process -> process.actions().size()).toArray();
        int totalActions = Arrays.stream(limits).sum();
        RuntimeState initial = new RuntimeState(topology);
        Node root = new Node(initial, new int[processes.size()], null, null, 0, null);
        Map<String, Integer> seen = new HashMap<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        seen.put(candidateKey(new int[processes.size()], initial), 0);
        List<VerificationModels.RejectedTransition> rejected = new ArrayList<>();
        int exploredStates = 1;
        int exploredTransitions = 0;

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (complete(node.pc, limits)) {
                continue;
            }
            if (node.depth >= bounds.maxDepth()) {
                return reportBound(bounds, exploredStates, exploredTransitions, queue.size(), rejected,
                        "已达到显式交错深度上限 " + bounds.maxDepth() + "，尚未证明安全");
            }
            for (int processIndex = 0; processIndex < processes.size(); processIndex++) {
                if (node.pc[processIndex] >= limits[processIndex]) {
                    continue;
                }
                ScenarioProcess process = processes.get(processIndex);
                ScenarioAction action = process.actions().get(node.pc[processIndex]);
                RuntimeState next = node.state.copy();
                VerificationModels.StateSnapshot before = StateRepresentations.snapshot(node.state);
                TransitionResult result = engine.apply(next, action);
                exploredTransitions++;
                List<InvariantViolation> violations = result.accepted() ? checker.check(next) : List.of();
                if (!result.accepted()) {
                    addRejection(rejected, process.id(), action, result, node.depth + 1);
                    continue;
                }
                int[] nextPc = node.pc.clone();
                nextPc[processIndex]++;
                VerificationModels.TraceStep step = new VerificationModels.TraceStep(
                        node.depth + 1, process.id(), action, true, null, null,
                        result.checks(), result.effects(), violations, before,
                        StateRepresentations.snapshot(next));
                if (!violations.isEmpty()) {
                    return counterexample(bounds, exploredStates, exploredTransitions, queue.size(), rejected,
                            reconstruct(node, step), violations.get(0));
                }
                String key = candidateKey(nextPc, next);
                if (seen.containsKey(key)) {
                    continue;
                }
                if (exploredStates + 1 > bounds.maxStates()) {
                    return reportBound(bounds, exploredStates, exploredTransitions, queue.size(), rejected,
                            "已达到显式状态数量上限 " + bounds.maxStates() + "，尚未证明安全");
                }
                seen.put(key, node.depth + 1);
                exploredStates++;
                queue.addLast(new Node(next, nextPc, node, process.id(), node.depth + 1, step));
            }
        }
        return new VerificationModels.VerificationReport(
                VerificationModels.VerificationConclusion.SAFE,
                "在深度 " + Math.min(bounds.maxDepth(), totalActions) + "、状态上限 " + bounds.maxStates()
                        + " 内穷举全部 " + totalActions + " 个程序有序交错且未发现不变量违反",
                bounds, exploredStates, exploredTransitions, 0, 0, List.of(), rejected,
                List.of(), "精确合并：键=程序计数+区段/道岔/进路/故障规范化状态；当前场景的进路结构或资源不满足安全对称归并条件");
    }

    private void addRejection(List<VerificationModels.RejectedTransition> rejected, String processId,
                              ScenarioAction action, TransitionResult result, int depth) {
        String key = processId + ":" + action.id() + ":" + result.rejectionCode();
        for (int index = 0; index < rejected.size(); index++) {
            VerificationModels.RejectedTransition existing = rejected.get(index);
            String existingKey = existing.processId() + ":" + existing.action().id() + ":" + existing.code();
            if (existingKey.equals(key)) {
                rejected.set(index, new VerificationModels.RejectedTransition(
                        existing.processId(), existing.action(), existing.code(), existing.reason(),
                        existing.guards(), existing.representativeDepth(), existing.occurrences() + 1));
                return;
            }
        }
        rejected.add(new VerificationModels.RejectedTransition(processId, action, result.rejectionCode(),
                result.rejectionReason(), result.checks(), depth, 1));
    }

    private VerificationModels.VerificationReport counterexample(VerificationModels.SearchBounds bounds,
            int exploredStates, int exploredTransitions, int queueSize,
            List<VerificationModels.RejectedTransition> rejected, List<VerificationModels.TraceStep> trace,
            InvariantViolation firstViolation) {
        return new VerificationModels.VerificationReport(
                VerificationModels.VerificationConclusion.COUNTEREXAMPLE,
                "发现长度 " + trace.size() + " 的排序最小反例，首个违反：" + firstViolation.invariant() + " — " + firstViolation.detail(),
                bounds, exploredStates, exploredTransitions, queueSize, trace.size(), trace, rejected,
                List.of(), "精确合并：键=程序计数+区段/道岔/进路/故障规范化状态；反例沿 BFS 父链完整回放");
    }

    private VerificationModels.VerificationReport reportBound(VerificationModels.SearchBounds bounds,
            int exploredStates, int exploredTransitions, int queueSize,
            List<VerificationModels.RejectedTransition> rejected, String reason) {
        return new VerificationModels.VerificationReport(
                VerificationModels.VerificationConclusion.BOUND_REACHED, reason, bounds,
                exploredStates, exploredTransitions, queueSize, 0, List.of(), rejected,
                List.of(), "仅做精确合并，未把达到上限解释为安全证明");
    }

    private List<VerificationModels.TraceStep> reconstruct(Node node, VerificationModels.TraceStep finalStep) {
        List<VerificationModels.TraceStep> steps = new ArrayList<>();
        Node current = node;
        while (current != null && current.step != null) {
            steps.add(current.step);
            current = current.parent;
        }
        Collections.reverse(steps);
        steps.add(finalStep);
        return List.copyOf(steps);
    }

    private boolean complete(int[] pc, int[] limits) {
        for (int index = 0; index < pc.length; index++) {
            if (pc[index] < limits[index]) {
                return false;
            }
        }
        return true;
    }

    private String candidateKey(int[] pc, RuntimeState state) {
        return Arrays.toString(pc) + "|" + StateRepresentations.canonicalKey(state);
    }

    private record Node(RuntimeState state, int[] pc, Node parent, String processId, int depth,
                        VerificationModels.TraceStep step) {
    }
}
