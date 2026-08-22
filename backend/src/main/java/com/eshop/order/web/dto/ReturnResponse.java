package com.eshop.order.web.dto;

import com.eshop.order.domain.OrderReturn;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A return request, as the customer sees it.
 *
 * <p>{@code totalQuantity} is summed from the return's lines, which are lazy, so this must be built
 * while the session is open.
 */
@Schema(name = "ReturnResponse")
public record ReturnResponse(UUID id, String returnNumber, OrderReturn.Status status,
                             OrderReturn.Reason reason, int totalQuantity, Instant createdAt) {

    public static ReturnResponse from(OrderReturn orderReturn) {
        return new ReturnResponse(
                orderReturn.getId(),
                orderReturn.getReturnNumber(),
                orderReturn.getStatus(),
                orderReturn.getReason(),
                orderReturn.totalQuantity(),
                orderReturn.getCreatedAt());
    }
}
