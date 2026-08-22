package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Create or replace a saved address. */
@Schema(name = "AddressRequest")
public record AddressRequest(

        @Size(max = 40)
        String label,

        @NotBlank
        @Size(max = 120)
        String recipientName,

        @NotBlank
        @Pattern(regexp = "^(\\+20|0)?1[0125][0-9]{8}$", message = "رقم الهاتف غير صحيح")
        String phone,

        @Pattern(regexp = "^[A-Z]{2}$")
        String countryCode,

        @NotBlank
        @Size(max = 60)
        String governorate,

        @NotBlank
        @Size(max = 80)
        String city,

        @Size(max = 80)
        String area,

        @NotBlank
        @Size(max = 160)
        String street,

        @Size(max = 40)
        String building,

        @Size(max = 40)
        String apartment,

        @Size(max = 20)
        String floor,

        @Schema(description = "Nearby reference point — how addresses are actually given locally")
        @Size(max = 160)
        String landmark,

        @Size(max = 12)
        String postalCode,

        @DecimalMin("-90.0") @DecimalMax("90.0")
        BigDecimal latitude,

        @DecimalMin("-180.0") @DecimalMax("180.0")
        BigDecimal longitude,

        Boolean defaultShipping,

        Boolean defaultBilling
) {
    public String countryCodeOrDefault() {
        return countryCode == null || countryCode.isBlank() ? "EG" : countryCode;
    }
}
