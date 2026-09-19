package dev.railsandbox.verify;

public record Diagnostic(boolean error, String code, String message) {
    public static Diagnostic error(String code, String message) {
        return new Diagnostic(true, code, message);
    }

    public static Diagnostic warning(String code, String message) {
        return new Diagnostic(false, code, message);
    }
}
