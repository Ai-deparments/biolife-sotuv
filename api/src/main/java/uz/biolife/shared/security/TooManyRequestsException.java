package uz.biolife.shared.security;

/** Too many attempts (login lockout) → 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) { super(message); }
}
