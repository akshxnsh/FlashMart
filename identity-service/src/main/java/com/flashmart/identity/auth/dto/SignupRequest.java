package com.flashmart.identity.auth.dto;

import com.flashmart.identity.auth.Emails;
import com.flashmart.identity.auth.validation.PasswordBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/signup}. Name and email are normalised in the
 * constructor, so validation runs on what will be stored: {@code " A@X.com "}
 * is checked (and saved) as {@code a@x.com}. The password is never touched:
 * spaces in a password are part of it.
 */
public record SignupRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @PasswordBytes String password
) {

    public SignupRequest {
        name = name == null ? null : name.trim();
        email = Emails.normalize(email);
    }

    // Records print every component; a password must never reach a log line.
    @Override
    public String toString() {
        return "SignupRequest[name=" + name + ", email=" + email + ", password=***]";
    }
}
