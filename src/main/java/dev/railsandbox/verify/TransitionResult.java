package dev.railsandbox.verify;

import java.util.List;

public record TransitionResult(boolean accepted, String rejectionCode, String rejectionReason,
                               List<GuardCheck> checks, List<String> effects) {
    public static TransitionResult rejected(String code, String reason, List<GuardCheck> checks) {
        return new TransitionResult(false, code, reason, checks, List.of());
    }

    public static TransitionResult accepted(List<GuardCheck> checks, List<String> effects) {
        return new TransitionResult(true, null, null, checks, effects);
    }
}
