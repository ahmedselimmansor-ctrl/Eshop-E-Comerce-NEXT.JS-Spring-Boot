package com.eshop.common.exception;

import java.io.Serial;

/**
 * Thrown by services when a lookup misses.
 *
 * <p>Services never return {@code null} for a "load this by id" call - the caller would have to
 * remember to check, and forgetting turns a clean 404 into a 500. Ownership checks also raise this
 * rather than {@link ErrorCode#FORBIDDEN}, so probing another customer's order id cannot be used to
 * learn whether that id exists.
 */
public class NotFoundException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String developerMessage) {
        super(ErrorCode.NOT_FOUND, developerMessage);
    }

    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " not found: " + id);
    }
}
