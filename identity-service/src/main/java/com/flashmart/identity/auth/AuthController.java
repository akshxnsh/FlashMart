package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.LoginRequest;
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
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return signupService.signup(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(request);
    }
}
