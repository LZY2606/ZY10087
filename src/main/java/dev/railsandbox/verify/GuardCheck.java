package dev.railsandbox.verify;

public record GuardCheck(String name, boolean satisfied, String detail) {
    public static GuardCheck of(boolean satisfied, String name, String detail) {
        return new GuardCheck(name, satisfied, detail);
    }
}
