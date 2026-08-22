package com.eshop.common.exception;

import java.io.Serial;

/** A request that is well-formed and authorised but cannot be applied to the current state. */
public class ConflictException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictException(String developerMessage) {
        super(ErrorCode.CONFLICT, developerMessage);
    }

    public ConflictException(ErrorCode errorCode, String developerMessage) {
        super(errorCode, developerMessage);
    }
}
