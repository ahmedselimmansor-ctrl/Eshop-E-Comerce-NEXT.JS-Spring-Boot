package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password change for a signed-in user.
 *
 * <p>The current password is required even though the caller already holds a valid access token:
 * a stolen token should not be enough to lock the real owner out of their own account. A successful
 * change revokes every refresh-token family, ending sessions on any device the attacker still holds.
 */
@Schema(name = "ChangePasswordRequest")
public record ChangePasswordRequest(

        @NotBlank
        @Size(max = 128)
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 128, message = "كلمة المرور يجب أن تكون 8 أحرف على الأقل")
        String newPassword
) {
}
