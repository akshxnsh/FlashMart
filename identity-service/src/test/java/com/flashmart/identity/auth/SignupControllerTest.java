package com.flashmart.identity.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/auth/signup} through the real controller, validator,
 * encoder and Postgres.
 *
 * <p>NOT {@code @Transactional}: MockMvc runs on the test thread, so the
 * insert would join a test transaction, and the duplicate test's unique
 * violation would abort it. Rows are committed for real; this class owns
 * {@value #DOMAIN} and deletes it before and after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignupControllerTest {

    private static final String DOMAIN = "signup.test";
    private static final String GOOD_PASSWORD = "correct horse";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
    }

    private ResultActions signup(String json) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String name, String email, String password) {
        StringBuilder json = new StringBuilder("{");
        if (name != null) json.append("\"name\":\"").append(name).append("\",");
        if (email != null) json.append("\"email\":\"").append(email).append("\",");
        if (password != null) json.append("\"password\":\"").append(password).append("\",");
        if (json.length() > 1) json.setLength(json.length() - 1);
        return json.append("}").toString();
    }

    private Map<String, Object> row(String email) {
        return jdbc.queryForMap("SELECT id, name, email, password_hash FROM users WHERE email = ?", email);
    }

    @Test
    void validSignupIs201WithIdNameEmailAndStoresABcryptHash() throws Exception {
        signup(body("Priya Sharma", "priya@" + DOMAIN, GOOD_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Priya Sharma"))
                .andExpect(jsonPath("$.email").value("priya@" + DOMAIN))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString("bcrypt"))))
                .andExpect(content().string(not(containsString("$2a$"))));

        String hash = (String) row("priya@" + DOMAIN).get("password_hash");
        assertTrue(hash.startsWith("{bcrypt}$2a$10$"), "expected a cost-10 bcrypt hash with its id prefix: " + hash);
        assertTrue(encoder.matches(GOOD_PASSWORD, hash));
    }

    @Test
    void mixedCaseAndPaddedEmailIsStoredTrimmedAndLowercase() throws Exception {
        signup(body("Priya", "  PrIyA.Sharma@SIGNUP.Test ", GOOD_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("priya.sharma@" + DOMAIN));
        assertEquals(1, AuthRows.userCount(jdbc, "priya.sharma@" + DOMAIN));
    }

    @Test
    void nameIsStoredTrimmed() throws Exception {
        signup(body("  Priya  ", "trim@" + DOMAIN, GOOD_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Priya"));
        assertEquals("Priya", row("trim@" + DOMAIN).get("name"));
    }

    @Test
    void duplicateEmailInAnyCaseIs409EmailTakenAndKeepsOneRow() throws Exception {
        signup(body("First", "dup@" + DOMAIN, GOOD_PASSWORD)).andExpect(status().isCreated());

        signup(body("Second", "DUP@" + DOMAIN.toUpperCase(), "another password"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").isString());

        assertEquals(1, AuthRows.userCount(jdbc, "dup@" + DOMAIN));
        assertEquals("First", row("dup@" + DOMAIN).get("name"));
    }

    // ---- passwords are measured in UTF-8 bytes ----

    @Test
    void password72AsciiBytesIsAccepted() throws Exception {
        signup(body("P", "p72@" + DOMAIN, "a".repeat(72))).andExpect(status().isCreated());
    }

    @Test
    void password73AsciiBytesIsRejected() throws Exception {
        signup(body("P", "p73@" + DOMAIN, "a".repeat(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid fields: [password]"));
        assertEquals(0, AuthRows.userCount(jdbc, "p73@" + DOMAIN));
    }

    @Test
    void multibyte72BytesIsAcceptedEvenThoughOnly36Characters() throws Exception {
        String password = "é".repeat(36);
        assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);
        signup(body("P", "mb72@" + DOMAIN, password)).andExpect(status().isCreated());
        assertTrue(encoder.matches(password, (String) row("mb72@" + DOMAIN).get("password_hash")));
    }

    @Test
    void multibyte73BytesIsRejectedEvenThoughOnly37Characters() throws Exception {
        String password = "é".repeat(36) + "a";
        assertEquals(37, password.length());
        assertEquals(73, password.getBytes(StandardCharsets.UTF_8).length);
        signup(body("P", "mb73@" + DOMAIN, password))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [password]"));
    }

    @Test
    void minimumIsAlsoBytes4TwoByteCharactersAre8BytesAndAccepted() throws Exception {
        signup(body("P", "mb8@" + DOMAIN, "éééé")).andExpect(status().isCreated());
    }

    @Test
    void password7BytesIsRejected() throws Exception {
        signup(body("P", "p7@" + DOMAIN, "1234567"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [password]"));
    }

    // ---- every other bad field ----

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\":\"a@signup.test\",\"password\":\"correct horse\"}",
            "{\"name\":\"\",\"email\":\"a@signup.test\",\"password\":\"correct horse\"}",
            "{\"name\":\"   \",\"email\":\"a@signup.test\",\"password\":\"correct horse\"}"
    })
    void missingOrBlankNameIs400(String json) throws Exception {
        signup(json).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid fields: [name]"));
    }

    @Test
    void name256CharactersIs400And255IsAccepted() throws Exception {
        signup(body("n".repeat(256), "n256@" + DOMAIN, GOOD_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [name]"));
        signup(body("n".repeat(255), "n255@" + DOMAIN, GOOD_PASSWORD)).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-email", "@signup.test", "a@", "a b@signup.test"})
    void badEmailIs400(String email) throws Exception {
        signup(body("P", email, GOOD_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid fields: [email]"));
    }

    @Test
    void missingEmailIs400() throws Exception {
        signup(body("P", null, GOOD_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [email]"));
    }

    @Test
    void email256CharactersIs400() throws Exception {
        String email = "e".repeat(256 - ("@" + DOMAIN).length()) + "@" + DOMAIN;
        assertEquals(256, email.length());
        signup(body("P", email, GOOD_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [email]"));
    }

    @Test
    void missingPasswordIs400() throws Exception {
        signup(body("P", "nopw@" + DOMAIN, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [password]"));
    }

    @Test
    void everyBadFieldIsNamedButNoValueIsEchoed() throws Exception {
        signup(body("", "nope", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid fields: [email, name, password]"))
                .andExpect(content().string(not(containsString("short"))));
    }

    @Test
    void brokenJsonIs400InvalidRequest() throws Exception {
        signup("{\"name\":").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void nonJsonContentTypeIs415InvalidRequest() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
