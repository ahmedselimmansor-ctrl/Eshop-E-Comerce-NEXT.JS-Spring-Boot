package com.eshop.common.exception;

import lombok.Getter;

import java.io.Serial;

/**
 * Raised by the rate-limiting filter once a bucket is drained.
 *
 * <p>{@code retryAfterSeconds} becomes the {@code Retry-After} response header so a well-behaved
 * client can back off precisely instead of polling.
 */
@Getter
public class RateLimitExceededException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;
    private final long limit;

    public RateLimitExceededException(long retryAfterSeconds, long limit) {
        super(ErrorCode.RATE_LIMITED, "Rate limit of " + limit + " exceeded; retry in " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit = limit;
        withProperty("retryAfterSeconds", retryAfterSeconds);
        withProperty("limit", limit);
    }
}
