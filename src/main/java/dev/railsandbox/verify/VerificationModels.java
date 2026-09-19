package dev.railsandbox.verify;

import dev.railsandbox.domain.Models.ScenarioAction;

import java.util.List;
import java.util.Map;

public final class VerificationModels {
    private VerificationModels() {
    }

    public record SearchBounds(int maxDepth, int maxStates) {
    }

    public enum VerificationConclusion {
        SAFE,
        COUNTEREXAMPLE,
        BOUND_REACHED
    }

    public record StateSnapshot(
            Map<String, String> routeStatuses,
            Map<String, List<String>> sectionOccupants,
            Map<String, List<String>> sectionLockers,
            Map<String, List<String>> switchLockers,
            Map<String, String> switchPositions,
            List<String> releasedSections,
            List<String> faultyDevices
    ) {
    }

    public record TraceStep(
            int step,
            String processId,
            ScenarioAction action,
            boolean accepted,
            String rejectionCode,
            String rejectionReason,
            List<GuardCheck> guards,
            List<String> effects,
            List<InvariantViolation> violations,
            StateSnapshot before,
            StateSnapshot after
    ) {
    }

    public record RejectedTransition(
            String processId,
            ScenarioAction action,
            String code,
            String reason,
            List<GuardCheck> guards,
            int representativeDepth,
            int occurrences
    ) {
    }

    public record SymmetryGroup(String basis, List<String> routeIds) {
    }

    public record VerificationReport(
            VerificationConclusion conclusion,
            String conclusionReason,
            SearchBounds bounds,
            int exploredStates,
            int exploredTransitions,
            int remainingQueue,
            int counterexampleLength,
            List<TraceStep> counterexample,
            List<RejectedTransition> rejectedTransitions,
            List<SymmetryGroup> symmetryGroups,
            String reductionBasis
    ) {
    }
}
