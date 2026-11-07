package com.flashmart.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of refresh and logout. A malformed but present token is not a 400: it simply matches nothing. */
public record RefreshTokenRequest(@NotBlank String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=***]";
    }
}
