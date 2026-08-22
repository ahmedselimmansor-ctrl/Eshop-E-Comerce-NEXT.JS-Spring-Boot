package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial profile update. Every field is optional; {@code null} means "leave unchanged".
 *
 * <p>Email is not editable here. Changing the address that receives password-reset links is an
 * account-takeover primitive, so it runs through its own verify-then-swap flow.
 */
@Schema(name = "UpdateProfileRequest")
public record UpdateProfileRequest(

        @Size(min = 2, max = 80)
        String firstName,

        @Size(max = 80)
        String lastName,

        @Pattern(regexp = "^(\\+20|0)?1[0125][0-9]{8}$", message = "رقم الهاتف غير صحيح")
        String phone,

        @Pattern(regexp = "^(ar|en)$")
        String locale,

        @Size(max = 1024)
        String avatarUrl,

        Boolean marketingOptIn
) {
}
