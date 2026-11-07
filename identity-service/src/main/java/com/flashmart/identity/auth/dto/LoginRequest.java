package com.flashmart.identity.auth.dto;

import com.flashmart.identity.auth.Emails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/auth/login}. Only presence is validated: a malformed email
 * or a password of the wrong length simply cannot match, and answers the same
 * 401 as any other failed login. The email is normalised exactly as at signup.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotNull String password
) {

    public LoginRequest {
        email = Emails.normalize(email);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
