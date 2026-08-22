package com.eshop.common.exception;

import lombok.Getter;

import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base type for every failure the application raises deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means the HTTP status, the stable machine code, and the bilingual
 * default message all travel with the exception, so {@link GlobalExceptionHandler} never has to
 * guess. Anything thrown that is <em>not</em> a {@code BusinessException} is treated as a defect:
 * it is logged with a stack trace and reported to the client as a generic
 * {@link ErrorCode#INTERNAL_ERROR}.
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** Field-level detail, surfaced in the {@code errors[]} array of the problem document. */
    private final List<FieldViolation> violations = new ArrayList<>();

    /** Extra machine-readable context, merged into the problem document as top-level members. */
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.messageEn());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String developerMessage) {
        super(developerMessage);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String developerMessage, Throwable cause) {
        super(developerMessage, cause);
        this.errorCode = errorCode;
    }

    public BusinessException withViolation(String field, String code, String message, Object rejectedValue) {
        violations.add(new FieldViolation(field, code, message, rejectedValue));
        return this;
    }

    public BusinessException withProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /**
     * A single field-level problem.
     *
     * @param field         JSON path of the offending field, e.g. {@code items[0].quantity}
     * @param code          stable machine code, e.g. {@code MAX_AVAILABLE}
     * @param message       human-readable text in the caller's locale
     * @param rejectedValue the value that was submitted; omitted for anything sensitive
     */
    public record FieldViolation(String field, String code, String message, Object rejectedValue) {
    }
}
