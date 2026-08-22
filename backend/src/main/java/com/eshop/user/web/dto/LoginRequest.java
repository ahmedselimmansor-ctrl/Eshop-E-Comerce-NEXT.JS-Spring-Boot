package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "LoginRequest")
public record LoginRequest(

        /** Email address or Egyptian mobile number — the login form has one field for both. */
        @NotBlank
        @Size(max = 191)
        String emailOrPhone,

        @NotBlank
        @Size(max = 128)
        String password,

        /**
         * Extends the refresh-token lifetime on this device. Ignored by mobile clients, which always
         * get the full window because reauthenticating on a phone is disproportionately annoying.
         */
        Boolean rememberMe
) {
    public boolean rememberMeOrDefault() {
        return Boolean.TRUE.equals(rememberMe);
    }
}
