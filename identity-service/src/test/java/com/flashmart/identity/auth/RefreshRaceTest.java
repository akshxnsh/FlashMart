package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.SignupRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two refreshes of the SAME token at once, on real Postgres, through
 * {@link TokenService} (the controller's 200/401 mapping is tested in
 * {@link RefreshControllerTest}). A result is "200" (a new pair), "401"
 * ({@link InvalidRefreshTokenException}) or "500" (anything else).
 *
 * <p>Every round must end with: exactly one 200 and one 401; the presented
 * token revoked; and, the accepted cost in decisions.md "Day 8" point 4 (the
 * two-tab case), the WINNER's new token revoked too, because the loser saw a
 * revoked token, treated it as reuse and revoked every live token of the
 * user. So the user has 0 live tokens and exactly 2 rows (old + winner's
 * new; the loser inserts nothing).
 *
 * <ul>
 *   <li><b>Forced:</b> another connection holds {@code SELECT ... FOR UPDATE}
 *   on the token row; both refreshes start and the test waits until
 *   {@code pg_stat_activity} shows both blocked on a lock (both are inside
 *   their CAS). Releasing the lock lets them collide on the row: one wins the
 *   CAS, the other waits for the winner's transaction and then matches 0
 *   rows.</li>
 *   <li><b>Free-running:</b> a barrier releases both threads; overlap inside
 *   Postgres is not controlled. The rule above holds in either order.</li>
 * </ul>
 *
 * <p>NOT {@code @Transactional}; this class owns {@value #DOMAIN}.
 */
@SpringBootTest
class RefreshRaceTest {

    private static final String DOMAIN = "race.refresh.test";
    private static final int FORCED_ROUNDS = 20;
    private static final int FREE_ROUNDS = 50;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TokenTransactions transactions;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private long buyer;

    @BeforeEach
    void buyer() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
        buyer = signupService.signup(new SignupRequest("Racer", "racer@" + DOMAIN, "correct horse")).id();
    }

    @AfterEach
    void cleanUp() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
    }

    private String outcome(String token) {
        try {
            tokenService.refresh(token);
            return "200";
        } catch (InvalidRefreshTokenException e) {
            return "401";
        } catch (Throwable t) {
            return "500 " + t;
        }
    }

    /** A clean slate per round: no rows left from earlier rounds, then one fresh token. */
    private String freshToken() {
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", buyer);
        return transactions.issue(buyer).refreshToken();
    }

    private String checkRound(int round, String token, List<String> outcomes) {
        long ok = outcomes.stream().filter("200"::equals).count();
        long rejected = outcomes.stream().filter("401"::equals).count();
        boolean oldRevoked = AuthRows.revokedAt(jdbc, token) != null;
        long live = AuthRows.liveTokens(jdbc, buyer);
        long rows = AuthRows.allTokens(jdbc, buyer);
        boolean good = ok == 1 && rejected == 1 && oldRevoked && live == 0 && rows == 2;
        return good ? null : "round " + round + ": " + outcomes + ", old revoked=" + oldRevoked
                + ", live=" + live + ", rows=" + rows;
    }

    @Test
    void forcedCollisionGivesOne200AndOne401AndTheWinnersNewTokenIsRevoked() throws Exception {
        List<String> broken = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < FORCED_ROUNDS; round++) {
                String token = freshToken();
                List<Future<String>> futures = new ArrayList<>();
                try (Connection blocker = dataSource.getConnection()) {
                    blocker.setAutoCommit(false);
                    try (PreparedStatement st = blocker.prepareStatement(
                            "SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE")) {
                        st.setString(1, RefreshTokens.hash(token));
                        try (ResultSet rs = st.executeQuery()) {
                            assertTrue(rs.next(), "the token row must exist");
                        }
                    }
                    futures.add(pool.submit(() -> outcome(token)));
                    futures.add(pool.submit(() -> outcome(token)));
                    awaitLockWaiters(2);
                    blocker.commit();
                }
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                String problem = checkRound(round, token, outcomes);
                if (problem != null) {
                    broken.add(problem);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(broken.isEmpty(), "forced: " + broken.size() + " of " + FORCED_ROUNDS + " rounds broke:\n"
                + String.join("\n", broken));
    }

    @Test
    void freeRunningRaceGivesOne200AndOne401AndTheWinnersNewTokenIsRevoked() throws Exception {
        List<String> broken = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < FREE_ROUNDS; round++) {
                String token = freshToken();
                CyclicBarrier go = new CyclicBarrier(2);
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        go.await(10, TimeUnit.SECONDS);
                        return outcome(token);
                    }));
                }
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                String problem = checkRound(round, token, outcomes);
                if (problem != null) {
                    broken.add(problem);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(broken.isEmpty(), "free-running: " + broken.size() + " of " + FREE_ROUNDS
                + " rounds broke:\n" + String.join("\n", broken));
    }

    private void awaitLockWaiters(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting >= n) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the two refreshes never both blocked on the row lock");
    }
}
