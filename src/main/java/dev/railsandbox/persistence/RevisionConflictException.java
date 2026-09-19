package dev.railsandbox.persistence;

public class RevisionConflictException extends RuntimeException {
    private final Object body;

    public RevisionConflictException(String message, Object body) {
        super(message);
        this.body = body;
    }

    public Object body() {
        return body;
    }
}
