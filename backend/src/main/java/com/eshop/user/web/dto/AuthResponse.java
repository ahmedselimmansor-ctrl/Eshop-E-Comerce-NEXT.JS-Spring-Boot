package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Payload of a successful register, login, or OAuth exchange. */
@Schema(name = "AuthResponse")
public record AuthResponse(
        UserResponse user,
        TokenPair tokens
) {
}
