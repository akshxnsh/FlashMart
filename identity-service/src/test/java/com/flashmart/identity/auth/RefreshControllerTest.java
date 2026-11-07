package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.LoginRequest;
import com.flashmart.identity.auth.dto.SignupRequest;
import com.flashmart.identity.auth.dto.TokenResponse;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh and logout through the real controller, transactions and Postgres
 * (decisions.md, "Day 8", points 3 and 4). Every service call commits, so
 * state is read back with plain queries after the call has returned: what is
 * seen is what was committed.
 *
 * <p>{@link RefreshTokens} is a spy (real behaviour unless a test overrides
 * it) so one test can force a token whose hash already exists and make the
 * insert fail. NOT {@code @Transactional}; this class owns {@value #DOMAIN}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshControllerTest {

    private static final String DOMAIN = "refresh.test";
    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private LoginService loginService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private RefreshTokens refreshTokens;

    @Value("${identity.jwt.secret}")
    private String secret;

    private long buyer;
    private long otherBuyer;

    @BeforeEach
    void buyers() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
        buyer = signupService.signup(new SignupRequest("Buyer", "buyer@" + DOMAIN, PASSWORD)).id();
        otherBuyer = signupService.signup(new SignupRequest("Other", "other@" + DOMAIN, PASSWORD)).id();
    }

    @AfterEach
    void cleanUp() {
        reset(refreshTokens);
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
    }

    private String login(String who) {
        return loginService.login(new LoginRequest(who + "@" + DOMAIN, PASSWORD)).refreshToken();
    }

    private ResultActions call(String path, String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":" + json.writeValueAsString(refreshToken) + "}"));
    }

    private JsonNode refreshOk(String refreshToken) throws Exception {
        String body = call("refresh", refreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void refreshRejected(String refreshToken) throws Exception {
        call("refresh", refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    // ---- login now issues a refresh token ----

    @Test
    void loginReturnsARefreshTokenThatIsStoredOnlyAsItsHashAndExpiresInSevenDaysOnTheDbClock() {
        TokenResponse login = loginService.login(new LoginRequest("buyer@" + DOMAIN, PASSWORD));
        String raw = login.refreshToken();
        assertEquals(43, raw.length(), "32 bytes as base64url without padding");
        assertTrue(raw.matches("[A-Za-z0-9_-]{43}"), raw);
        assertEquals(900, login.expiresIn());

        Map<String, Object> row = jdbc.queryForMap("SELECT token_hash, user_id, revoked_at, "
                + "extract(epoch FROM expires_at - created_at) AS ttl FROM refresh_tokens WHERE user_id = ?", buyer);
        assertEquals(RefreshTokens.hash(raw), row.get("token_hash"));
        assertNull(row.get("revoked_at"));
        double ttl = ((Number) row.get("ttl")).doubleValue();
        assertTrue(Math.abs(ttl - 7 * 24 * 3600) < 1.0, "expires_at - created_at = " + ttl + " s");
        Long rawStored = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Long.class, raw);
        assertEquals(0L, rawStored);
    }

    // ---- rotation ----

    @Test
    void refreshRotatesTheOldTokenDiesAndTheNewOneWorks() throws Exception {
        String t1 = login("buyer");

        JsonNode first = refreshOk(t1);
        assertEquals(Set.of("accessToken", "refreshToken", "expiresIn"), Set.copyOf(first.propertyNames()));
        assertEquals(900, first.get("expiresIn").asInt());
        String t2 = first.get("refreshToken").asString();
        assertNotEquals(t1, t2);
        String sub = Jwts.parser().verifyWith(JwtIssuer.keyFrom(secret)).build()
                .parseSignedClaims(first.get("accessToken").asString()).getPayload().getSubject();
        assertEquals(Long.toString(buyer), sub);

        assertNotNull(AuthRows.revokedAt(jdbc, t1), "the presented token must be revoked");
        assertNull(AuthRows.revokedAt(jdbc, t2));

        String t3 = refreshOk(t2).get("refreshToken").asString();
        assertNotNull(AuthRows.revokedAt(jdbc, t2));
        assertNull(AuthRows.revokedAt(jdbc, t3));
        assertEquals(1, AuthRows.liveTokens(jdbc, buyer));
    }

    @Test
    void anExpiredTokenIs401AndChangesNothing() throws Exception {
        String live = login("buyer");
        String expired = "expired-token-for-test";
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES "
                        + "(?, ?, clock_timestamp() - interval '1 second', clock_timestamp() - interval '8 days')",
                buyer, RefreshTokens.hash(expired));
        long rowsBefore = AuthRows.allTokens(jdbc, buyer);

        refreshRejected(expired);

        assertNull(AuthRows.revokedAt(jdbc, expired), "an expired token is not revoked by a failed refresh");
        assertNull(AuthRows.revokedAt(jdbc, live), "an expired token is not reuse: nothing else is revoked");
        assertEquals(rowsBefore, AuthRows.allTokens(jdbc, buyer), "no new token was issued");
    }

    @Test
    void anUnknownTokenIs401AndChangesNothing() throws Exception {
        String live = login("buyer");
        refreshRejected("never-issued");
        refreshRejected("not base64 at all !!");
        assertNull(AuthRows.revokedAt(jdbc, live));
        assertEquals(1, AuthRows.allTokens(jdbc, buyer));
    }

    // ---- reuse ----

    @Test
    void reuseIs401AndRevokesEveryLiveTokenOfThatUserAndThatIsCommitted() throws Exception {
        String deviceA = login("buyer");
        String deviceB = login("buyer");
        String otherUsers = login("other");
        String rotated = refreshOk(deviceA).get("refreshToken").asString();

        refreshRejected(deviceA);  // already rotated: reuse

        // Read after the call returned, on another connection: committed state.
        assertEquals(0, AuthRows.liveTokens(jdbc, buyer));
        assertNotNull(AuthRows.revokedAt(jdbc, rotated));
        assertNotNull(AuthRows.revokedAt(jdbc, deviceB));
        refreshRejected(deviceB);
        refreshRejected(rotated);
        assertNull(AuthRows.revokedAt(jdbc, otherUsers), "another user's token is untouched");
        refreshOk(otherUsers);
    }

    // ---- a failed insert does not burn the old token ----

    @Test
    void aFailedInsertRollsBackTheCasAndTheOldTokenStillWorks() throws Exception {
        String t1 = login("buyer");
        String existing = login("other");
        // The next "new" token is one whose hash is already stored, so the
        // insert of the rotated token hits uk_refresh_tokens_token_hash.
        doReturn(existing).when(refreshTokens).newToken();

        assertThrows(DataIntegrityViolationException.class, () -> tokenService.refresh(t1));

        assertNull(AuthRows.revokedAt(jdbc, t1), "the CAS must roll back with the failed insert");
        assertEquals(1, AuthRows.allTokens(jdbc, buyer));
        reset(refreshTokens);
        refreshOk(t1);
    }

    // ---- logout ----

    @Test
    void logoutIs204AndThenRefreshWithThatTokenIs401() throws Exception {
        String t1 = login("buyer");
        call("logout", t1).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertNotNull(AuthRows.revokedAt(jdbc, t1));
        refreshRejected(t1);
    }

    @Test
    void refreshAfterLogoutCountsAsReuseAndRevokesTheUsersOtherTokens() throws Exception {
        // Recorded as-built: a logged-out token is a revoked token, so
        // presenting it to /refresh follows the reuse rule (decisions.md,
        // "Day 8", point 4) and logs the buyer out everywhere.
        String loggedOut = login("buyer");
        String otherDevice = login("buyer");
        call("logout", loggedOut).andExpect(status().isNoContent());
        refreshRejected(loggedOut);
        assertNotNull(AuthRows.revokedAt(jdbc, otherDevice));
    }

    @Test
    void logoutTwiceIs204BothTimesAndNeverRevokesOtherTokens() throws Exception {
        String t1 = login("buyer");
        String otherDevice = login("buyer");
        call("logout", t1).andExpect(status().isNoContent());
        call("logout", t1).andExpect(status().isNoContent());
        assertNull(AuthRows.revokedAt(jdbc, otherDevice), "logout never triggers the reuse response");
        assertEquals(1, AuthRows.liveTokens(jdbc, buyer));
    }

    @Test
    void logoutOfAnUnknownTokenIs204AndChangesNothing() throws Exception {
        String live = login("buyer");
        call("logout", "never-issued").andExpect(status().isNoContent());
        assertNull(AuthRows.revokedAt(jdbc, live));
    }

    @Test
    void missingOrBlankTokenIs400OnBothEndpoints() throws Exception {
        for (String path : new String[]{"refresh", "logout"}) {
            mvc.perform(post("/api/auth/" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            call(path, "   ").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("invalid fields: [refreshToken]"));
        }
    }
}
