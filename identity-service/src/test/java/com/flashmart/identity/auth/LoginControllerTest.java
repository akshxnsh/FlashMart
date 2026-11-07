package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.SignupRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/auth/login} through the real controller, encoder, issuer
 * and Postgres. Tokens are verified here with the same key the issuer used
 * (the test secret), the way the gateway will verify them.
 *
 * <p>The encoder is a spy so the unknown-email test can prove bcrypt still
 * runs. NOT {@code @Transactional}; this class owns {@value #DOMAIN}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginControllerTest {

    private static final String DOMAIN = "login.test";
    private static final String EMAIL = "buyer@" + DOMAIN;
    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private PasswordEncoder encoder;

    @Value("${identity.jwt.secret}")
    private String secret;

    private long userId;

    @BeforeEach
    void createBuyer() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
        userId = signupService.signup(new SignupRequest("Buyer", EMAIL, PASSWORD)).id();
        clearInvocations(encoder);
    }

    @AfterEach
    void cleanUp() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = "{\"email\":" + json.writeValueAsString(email)
                + ",\"password\":" + json.writeValueAsString(password) + "}";
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Jws<Claims> verifyToken(String token) {
        return Jwts.parser().verifyWith(JwtIssuer.keyFrom(secret)).build().parseSignedClaims(token);
    }

    @Test
    void goodLoginIs200WithAnHs256TokenCarryingExactlySubTypIatExp() throws Exception {
        String body = login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("accessToken").asString();

        Jws<Claims> jws = verifyToken(token);
        Claims claims = jws.getPayload();
        assertEquals("HS256", jws.getHeader().getAlgorithm());
        assertEquals(Set.of("sub", "typ", "iat", "exp"), claims.keySet());
        assertEquals(Long.toString(userId), claims.getSubject());
        assertEquals("user", claims.get("typ"));
        assertEquals(Duration.ofMinutes(15).toMillis(),
                claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    @Test
    void emailIsNormalisedBeforeLookup() throws Exception {
        login("  BUYER@Login.Test ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIs401InvalidCredentials() throws Exception {
        login(EMAIL, "wrong horse")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknownEmailGivesTheSameStatusAndBodyAsAWrongPassword() throws Exception {
        String wrongPassword = login(EMAIL, "wrong horse").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@" + DOMAIN, PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertEquals(wrongPassword, unknownEmail);
    }

    @Test
    void unknownEmailStillRunsOneBcryptCheckAgainstADummyHash() throws Exception {
        login("nobody@" + DOMAIN, PASSWORD).andExpect(status().isUnauthorized());
        verify(encoder, times(1)).matches(eq(PASSWORD), anyString());
    }

    @Test
    void aStoredHashWithNoAlgorithmPrefixIs401NotA500() throws Exception {
        // The seed's placeholder hashes look like this: no {bcrypt} prefix.
        jdbc.update("INSERT INTO users (name, email, password_hash) VALUES ('Seeded', ?, 'placeholder')",
                "seeded@" + DOMAIN);
        login("seeded@" + DOMAIN, "anything at all")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void storedPasswordPlusExtraBytesPast72IsRejected() throws Exception {
        String password72 = "p".repeat(72);
        signupService.signup(new SignupRequest("Long", "long@" + DOMAIN, password72));
        login("long@" + DOMAIN, password72).andExpect(status().isOk());
        // bcrypt itself ignores bytes past 72, so without the explicit check
        // this would log in.
        login("long@" + DOMAIN, password72 + "anything")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void missingFieldsAre400InvalidRequest() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid fields: [password]"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [email]"));
    }

    @Test
    void theResponseNeverCarriesThePasswordOrHash() throws Exception {
        String body = login(EMAIL, PASSWORD).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        assertEquals(Set.of("accessToken", "expiresIn"), Set.copyOf(node.propertyNames()));
        assertTrue(!body.contains(PASSWORD) && !body.contains("bcrypt"));
    }
}
