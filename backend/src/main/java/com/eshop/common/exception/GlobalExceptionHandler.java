package com.eshop.common.exception;

import com.eshop.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates everything thrown inside the web layer into an RFC 9457 problem document.
 *
 * <p>Two rules govern what reaches the client:
 * <ol>
 *   <li><b>Deliberate failures</b> ({@link BusinessException}) carry their own code and status and
 *       are logged at WARN without a stack trace - they are expected outcomes, not incidents.</li>
 *   <li><b>Everything else</b> is a defect. The real cause is logged at ERROR with the request id,
 *       and the client receives a generic {@link ErrorCode#INTERNAL_ERROR}. Exception messages,
 *       SQL fragments, and stack traces never cross the boundary.</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------------------------ app --

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("business_error code={} status={} detail=\"{}\" path={}",
                code.name(), code.status().value(), ex.getMessage(), request.getRequestURI());

        ProblemDetail problem = problem(code, code.message(RequestContext.locale()), request);
        if (!ex.getViolations().isEmpty()) {
            problem.setProperty("errors", ex.getViolations());
        }
        ex.getProperties().forEach(problem::setProperty);

        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof RateLimitExceededException rateLimited) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimited.getRetryAfterSeconds()));
        }
        return new ResponseEntity<>(problem, headers, code.status());
    }

    // ----------------------------------------------------------- validation --

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<BusinessException.FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            Object rejected = error instanceof FieldError fieldError ? fieldError.getRejectedValue() : null;
            violations.add(new BusinessException.FieldViolation(
                    field, error.getCode(), error.getDefaultMessage(), redactIfSensitive(field, rejected)));
        });
        return validationProblem(violations, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleParamValidation(ConstraintViolationException ex,
                                                               HttpServletRequest request) {
        List<BusinessException.FieldViolation> violations = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            violations.add(new BusinessException.FieldViolation(
                    field,
                    violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName().toUpperCase(),
                    violation.getMessage(),
                    redactIfSensitive(field, violation.getInvalidValue())));
        }
        return validationProblem(violations, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        return validationProblem(List.of(new BusinessException.FieldViolation(
                ex.getParameterName(), "REQUIRED", "Required parameter is missing", null)), request);
    }

    /**
     * A required header was not sent.
     *
     * <p>Without this, Spring's {@code MissingRequestHeaderException} falls through to the catch-all
     * and a client that simply forgot {@code Idempotency-Key} is told the server broke. It is a
     * malformed request, and a 400 naming the header is what lets the caller fix it.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex,
                                                             HttpServletRequest request) {
        return validationProblem(List.of(new BusinessException.FieldViolation(
                ex.getHeaderName(), "REQUIRED", "Required header is missing", null)), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return validationProblem(List.of(new BusinessException.FieldViolation(
                ex.getName(), "TYPE_MISMATCH", "Value is not of the expected type", ex.getValue())), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        // The parser message can echo raw payload fragments, so it is logged but never returned.
        log.warn("malformed_request path={} cause={}", request.getRequestURI(), ex.getMostSpecificCause().toString());
        return status(ErrorCode.MALFORMED_REQUEST, request);
    }

    // --------------------------------------------------------------- security --

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(BadCredentialsException ex,
                                                              HttpServletRequest request) {
        // Deliberately identical for "no such user" and "wrong password" - the distinction would
        // turn the login endpoint into an account-enumeration oracle.
        log.warn("authentication_failed path={} ip={}", request.getRequestURI(), RequestContext.clientIp());
        return status(ErrorCode.INVALID_CREDENTIALS, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        log.warn("unauthenticated path={} reason={}", request.getRequestURI(), ex.getClass().getSimpleName());
        return status(ErrorCode.UNAUTHENTICATED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex,
                                                            HttpServletRequest request) {
        log.warn("access_denied path={} user={} ip={}",
                request.getRequestURI(), RequestContext.userId().orElse("anonymous"), RequestContext.clientIp());
        return status(ErrorCode.FORBIDDEN, request);
    }

    // ------------------------------------------------------------- persistence --

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        log.warn("optimistic_lock path={} detail={}", request.getRequestURI(), ex.getMessage());
        return status(ErrorCode.CONFLICT, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        // Constraint names leak schema detail, so map to a generic duplicate and log the specifics.
        log.warn("data_integrity_violation path={} cause={}",
                request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return status(ErrorCode.DUPLICATE_RESOURCE, request);
    }

    // ---------------------------------------------------------------- protocol --

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex,
                                                          HttpServletRequest request) {
        return status(ErrorCode.NOT_FOUND, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        ProblemDetail problem = problem(ErrorCode.MALFORMED_REQUEST,
                "Method " + ex.getMethod() + " is not supported for this endpoint", request);
        problem.setStatus(HttpStatus.METHOD_NOT_ALLOWED);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problem);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request) {
        return status(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadSize(MaxUploadSizeExceededException ex,
                                                          HttpServletRequest request) {
        return status(ErrorCode.PAYLOAD_TOO_LARGE, request);
    }

    // --------------------------------------------------------------- fallback --

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled_exception path={} requestId={}", request.getRequestURI(), RequestContext.requestId(), ex);
        return status(ErrorCode.INTERNAL_ERROR, request);
    }

    // ----------------------------------------------------------------- helpers --

    private ResponseEntity<ProblemDetail> validationProblem(List<BusinessException.FieldViolation> violations,
                                                            HttpServletRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.message(RequestContext.locale()), request);
        problem.setProperty("errors", violations);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status()).body(problem);
    }

    private ResponseEntity<ProblemDetail> status(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.status())
                .body(problem(code, code.message(RequestContext.locale()), request));
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.messageEn());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", RequestContext.requestId());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private static final Map<String, Boolean> SENSITIVE_FIELDS = Map.of(
            "password", true, "newPassword", true, "currentPassword", true,
            "token", true, "refreshToken", true, "accessToken", true,
            "cardNumber", true, "cvv", true, "securityKey", true);

    /** Keeps credentials out of the echoed {@code rejectedValue}. */
    private static Object redactIfSensitive(String field, Object value) {
        return field != null && SENSITIVE_FIELDS.containsKey(field) ? "[REDACTED]" : value;
    }
}
