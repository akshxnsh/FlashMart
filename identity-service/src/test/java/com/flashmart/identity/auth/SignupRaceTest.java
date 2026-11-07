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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two simultaneous signups with the same email, on real Postgres, through
 * {@link SignupService} (the controller's mapping of its two results to 201
 * and 409 is tested in {@link SignupControllerTest}). Each round's outcome is
 * classified as 201 (a {@code SignupResponse}), 409 ({@link EmailTakenException})
 * or "500" (anything else), and the round must give exactly one 201, one 409
 * and one row.
 *
 * <p>Two races:
 * <ul>
 *   <li><b>Forced:</b> a separate connection holds {@code LOCK TABLE users IN
 *   SHARE MODE}, which blocks every INSERT. Both signups are started, and the
 *   test waits until {@code pg_stat_activity} shows BOTH blocked on a lock, so
 *   both have already hashed and are inside their INSERT. Then the lock is
 *   released and both inserts run at once: the loser waits on the winner's
 *   uncommitted unique-index entry, and gets 23505 when the winner commits.
 *   This is the path a sequential test never reaches.</li>
 *   <li><b>Free-running:</b> a barrier releases both threads together. Whether
 *   their inserts overlap inside Postgres is not controlled; the outcome rule
 *   is the same either way.</li>
 * </ul>
 *
 * <p>NOT {@code @Transactional}; this class owns {@value #DOMAIN}.
 */
@SpringBootTest
class SignupRaceTest {

    private static final String DOMAIN = "race.signup.test";
    private static final int FORCED_ROUNDS = 20;
    private static final int FREE_ROUNDS = 50;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        AuthRows.deleteUsersOfDomain(jdbc, DOMAIN);
    }

    private String outcome(String email, String name) {
        try {
            signupService.signup(new SignupRequest(name, email, "correct horse"));
            return "201";
        } catch (EmailTakenException e) {
            return "409";
        } catch (Throwable t) {
            return "500 " + t;
        }
    }

    private String checkRound(int round, String email, List<String> outcomes) {
        long rows = AuthRows.userCount(jdbc, email);
        boolean ok = outcomes.stream().filter("201"::equals).count() == 1
                && outcomes.stream().filter("409"::equals).count() == 1
                && rows == 1;
        return ok ? null : "round " + round + ": " + outcomes + ", rows=" + rows;
    }

    @Test
    void forcedCollisionGivesExactlyOne201AndOne409NeverA500() throws Exception {
        List<String> broken = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < FORCED_ROUNDS; round++) {
                String email = "forced" + round + "@" + DOMAIN;
                List<Future<String>> futures = new ArrayList<>();
                try (Connection blocker = dataSource.getConnection()) {
                    blocker.setAutoCommit(false);
                    try (Statement st = blocker.createStatement()) {
                        st.execute("LOCK TABLE users IN SHARE MODE");
                    }
                    futures.add(pool.submit(() -> outcome(email, "A")));
                    futures.add(pool.submit(() -> outcome(email, "B")));
                    awaitLockWaiters(2);
                    blocker.commit();
                }
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                String problem = checkRound(round, email, outcomes);
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
    void freeRunningRaceGivesExactlyOne201AndOne409NeverA500() throws Exception {
        List<String> broken = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < FREE_ROUNDS; round++) {
                String email = "free" + round + "@" + DOMAIN;
                CyclicBarrier go = new CyclicBarrier(2);
                List<Future<String>> futures = new ArrayList<>();
                for (String name : List.of("A", "B")) {
                    futures.add(pool.submit(() -> {
                        go.await(10, TimeUnit.SECONDS);
                        return outcome(email, name);
                    }));
                }
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                String problem = checkRound(round, email, outcomes);
                if (problem != null) {
                    broken.add(problem);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(broken.isEmpty(), "free-running: " + broken.size() + " of " + FREE_ROUNDS + " rounds broke:\n"
                + String.join("\n", broken));
    }

    /** Waits until at least {@code n} backends of this database are blocked on a lock. */
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
        throw new AssertionError("the two signups never both blocked on the table lock");
    }
}
