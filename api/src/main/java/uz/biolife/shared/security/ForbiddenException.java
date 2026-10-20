package uz.biolife.shared.security;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
