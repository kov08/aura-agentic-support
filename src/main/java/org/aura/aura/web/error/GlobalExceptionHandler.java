package org.aura.aura.web.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

// Central exception -> HTTP translation. Controllers throw; this maps.
// Bodies use ProblemDetail (RFC 9457): consistent, machine-readable, application/problem+json.
// Spring reads the HTTP status straight off the ProblemDetail returned here.
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

    // Client mistake: failed validation. 400 = "you erred; retrying as-is won't help."
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        pd.setTitle("Validation failed");
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        pd.setProperty("errors", errors); // structured detail clients can program against
        return pd;
    }

    // Broken/unparseable JSON -> also a client mistake -> 400.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is malformed");
        pd.setTitle("Malformed request");
        return pd;
    }

    // Catch-all. 500 = "my side broke." NEVER leak a stack trace; log server-side.
    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex) {
        // Full exception (with stack trace) goes to the server log only — the client body below
        // carries no internal detail, so we don't hand attackers a map of our internals.
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        pd.setTitle("Internal error");
        return pd;
    }

    // --- STUB for Day 8 (Resilience4j) ---------------------------------------
    // Upstream Claude failure -> 502; Claude timeout -> 504; Claude 429 -> pass through 429.
    // Mapping table is visible now; resilient handling wired to real SDK exceptions on Day 8.
    // -------------------------------------------------------------------------
}
