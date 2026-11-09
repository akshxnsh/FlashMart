package com.flashmart.identity.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.flashmart.identity.auth.dto.LoginRequest;
import com.flashmart.identity.auth.dto.RefreshTokenRequest;
import com.flashmart.identity.auth.dto.SignupRequest;
import com.flashmart.identity.auth.dto.SignupResponse;
import com.flashmart.identity.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer auth (decisions.md, "Day 8"). Reached through the gateway at
 * {@code /api/auth/**} once it exists; the gateway, not this service, checks
 * access tokens on other routes.
 */
@Tag(name = "Auth", description = "Public routes: no token needed.")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;
    private final TokenService tokenService;

    @Operation(summary = "Create a buyer account.")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return signupService.signup(request);
    }

    @Operation(summary = "Log in; returns an access token and a refresh token.")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(request);
    }

    @Operation(summary = "Swap a refresh token for a new token pair.")
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return tokenService.refresh(request.refreshToken());
    }

    /** Always 204 for a well-formed body: logging out an unknown or already revoked token is a no-op. */
    @Operation(summary = "Revoke a refresh token; always 204 for a well-formed body.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        tokenService.logout(request.refreshToken());
    }
}
