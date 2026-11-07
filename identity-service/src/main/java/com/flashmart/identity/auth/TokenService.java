package com.flashmart.identity.auth;

import com.flashmart.identity.auth.TokenTransactions.IssuedTokens;
import com.flashmart.identity.auth.TokenTransactions.RotateOutcome;
import com.flashmart.identity.auth.dto.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Refresh and logout. NOT transactional: each call's transaction (in
 * {@link TokenTransactions}) has committed by the time this maps its outcome,
 * so the 401 for a reused token is thrown only after the revoke-all is saved.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenTransactions transactions;
    private final JwtIssuer jwt;

    public TokenResponse refresh(String refreshToken) {
        RotateOutcome outcome = transactions.rotate(refreshToken);
        return switch (outcome) {
            case RotateOutcome.Rotated rotated -> response(rotated.tokens());
            case RotateOutcome.Invalid invalid -> throw new InvalidRefreshTokenException();
            case RotateOutcome.Reused reused -> throw new InvalidRefreshTokenException();
        };
    }

    public void logout(String refreshToken) {
        transactions.logout(refreshToken);
    }

    TokenResponse response(IssuedTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), jwt.accessTtlSeconds());
    }
}
