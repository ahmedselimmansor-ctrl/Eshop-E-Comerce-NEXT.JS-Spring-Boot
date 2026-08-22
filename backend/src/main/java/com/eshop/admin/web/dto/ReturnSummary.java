package com.eshop.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** A return raised against an order, as listed on the console's order screen. */
@Schema(name = "ReturnSummary")
public record ReturnSummary(UUID id, String returnNumber, String status, String reason,
                            int totalQuantity) {
}
