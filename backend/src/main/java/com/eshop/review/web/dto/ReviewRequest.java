package com.eshop.review.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Submit or edit a review.
 *
 * <p>There is deliberately no {@code isVerifiedPurchase} field - that flag is derived server-side
 * from a delivered order line. Media URLs must be ones the client obtained from
 * {@code POST /admin/media/upload-url}; arbitrary external URLs are rejected by the service.
 */
@Schema(name = "ReviewRequest")
public record ReviewRequest(

        @NotNull
        @Min(1) @Max(5)
        Short rating,

        @Size(max = 120)
        String title,

        @Size(max = 4000)
        String body,

        @Schema(description = "At most 5; extras are ignored")
        List<@Size(max = 1024) String> mediaUrls
) {
}
