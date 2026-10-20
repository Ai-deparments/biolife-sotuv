package uz.biolife.shared.web;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.biolife.shared.security.ForbiddenException;
import uz.biolife.shared.security.TooManyRequestsException;
import uz.biolife.shared.security.UnauthorizedException;

/** RFC 9457 problem details for every error; type = https://biolife.uz/errors/<code>. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) { return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage()); }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e) { return problem(HttpStatus.FORBIDDEN, "forbidden", e.getMessage()); }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail unauthorized(UnauthorizedException e) { return problem(HttpStatus.UNAUTHORIZED, "unauthorized", e.getMessage()); }

    @ExceptionHandler(TooManyRequestsException.class)
    ProblemDetail tooMany(TooManyRequestsException e) { return problem(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation", "Maydonlarni to'ldiring: " + String.join(", ",
            e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(Exception e) { return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation", e.getMessage()); }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        // Spring's own client errors (unknown path 404, wrong method 405, missing parameter 400) keep their status
        if (e instanceof org.springframework.web.ErrorResponse er) {
            HttpStatus s = HttpStatus.resolve(er.getStatusCode().value());
            if (s != null && s.is4xxClientError()) return problem(s, "request", er.getBody().getDetail() == null ? s.getReasonPhrase() : er.getBody().getDetail());
        }
        if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException || e instanceof org.springframework.beans.TypeMismatchException) {
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation", "So'rov ma'lumoti noto'g'ri");
        }
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Kutilmagan xato. Sahifani yangilab, qayta urinib ko'ring.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://biolife.uz/errors/" + code));
        return pd;
    }
}
