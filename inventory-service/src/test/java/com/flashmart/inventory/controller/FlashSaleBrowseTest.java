package com.flashmart.inventory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browse through the real controller, handler, service, queries and Postgres.
 * {@code @Transactional}: every row, including the {@code UPDATE} that cancels
 * the shared dev database's existing sales (so these tests see only their own
 * rows), rolls back. MockMvc runs on the test thread, so it joins that
 * transaction and sees the rows.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class FlashSaleBrowseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory emf;

    private final ObjectMapper json = new ObjectMapper();

    private long productId;

    @BeforeEach
    void emptyWorld() {
        jdbc.update("UPDATE flash_sales SET status = 'CANCELLED'");
        productId = jdbc.queryForObject(
                "INSERT INTO products (brand_id, name) VALUES (9001, 'browse-test-product') RETURNING id", Long.class);
    }

    /** A sale whose window is given as SQL intervals from the DATABASE clock; returns its id. */
    private long sale(String name, String startOffset, String endOffset, String status) {
        return jdbc.queryForObject("INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) VALUES "
                + "(9001, ?, now() + interval '" + startOffset + "', now() + interval '" + endOffset + "', ?) RETURNING id",
                Long.class, name, status);
    }

    private long liveSale(String name) {
        return sale(name, "-1 hour", "1 hour", "ACTIVE");
    }

    private void item(long saleId, int total, int available) {
        long p = jdbc.queryForObject("INSERT INTO products (brand_id, name) VALUES (9001, ?) RETURNING id", Long.class,
                "browse-product-" + saleId + "-" + available + "-" + System.nanoTime());
        jdbc.update("INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, total_stock, available_stock) "
                + "VALUES (?, ?, 100.00, ?, ?)", saleId, p, total, available);
    }

    private JsonNode page(Object page) throws Exception {
        String url = page == null ? "/api/flash-sales" : "/api/flash-sales?page=" + page;
        MvcResult r = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("sales").forEach(s -> ids.add(s.get("id").asLong()));
        return ids;
    }

    // --- paging ---

    @Test
    void noSalesIsAnEmptyPageWithoutNext() throws Exception {
        JsonNode p = page(0);
        assertThat(p.get("sales")).isEmpty();
        assertThat(p.get("hasNext").asBoolean()).isFalse();
        assertThat(p.get("page").asInt()).isZero();
        assertThat(p.get("size").asInt()).isEqualTo(10);
    }

    @Test
    void exactlyTenSalesIsOnePageWithoutNext() throws Exception {
        for (int i = 0; i < 10; i++) {
            liveSale("s" + i);
        }
        JsonNode p = page(0);
        assertThat(p.get("sales")).hasSize(10);
        assertThat(p.get("hasNext").asBoolean()).isFalse();
        assertThat(page(1).get("sales")).isEmpty();
    }

    @Test
    void elevenSalesGiveTenWithNextThenOne() throws Exception {
        for (int i = 0; i < 11; i++) {
            liveSale("s" + i);
        }
        JsonNode first = page(0);
        assertThat(first.get("sales")).hasSize(10);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        JsonNode second = page(1);
        assertThat(second.get("sales")).hasSize(1);
        assertThat(second.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void twentyFiveSalesPageAsTenTenFiveInOrderWithNoRepeats() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(sale("s" + i, (i - 30) + " minutes", "1 day", "ACTIVE")); // earlier start first
        }
        JsonNode p0 = page(0), p1 = page(1), p2 = page(2), p3 = page(3);
        assertThat(p0.get("sales")).hasSize(10);
        assertThat(p1.get("sales")).hasSize(10);
        assertThat(p2.get("sales")).hasSize(5);
        assertThat(p3.get("sales")).isEmpty();
        assertThat(p0.get("hasNext").asBoolean()).isTrue();
        assertThat(p1.get("hasNext").asBoolean()).isTrue();
        assertThat(p2.get("hasNext").asBoolean()).isFalse();
        List<Long> all = new ArrayList<>(ids(p0));
        all.addAll(ids(p1));
        all.addAll(ids(p2));
        assertThat(all).as("every sale once, ordered by start_time").containsExactlyElementsOf(created);
    }

    @Test
    void salesWithTheSameStartTimeAreOrderedByIdAcrossAPageBoundary() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            created.add(jdbc.queryForObject("INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                    + "VALUES (9001, ?, '2030-01-01T00:00:00Z', '2030-01-02T00:00:00Z', 'ACTIVE') RETURNING id",
                    Long.class, "tie" + i));
        }
        List<Long> all = new ArrayList<>(ids(page(0)));
        all.addAll(ids(page(1)));
        assertThat(all).containsExactlyElementsOf(created);
    }

    // --- which sales ---

    @Test
    void endedAndCancelledSalesAreHiddenAndScheduledAndLiveAreShownWithTheirState() throws Exception {
        long live = liveSale("live");
        long scheduled = sale("scheduled", "1 hour", "2 hours", "ACTIVE");
        sale("ended", "-2 hours", "-1 hour", "ACTIVE");
        sale("cancelled live", "-1 hour", "1 hour", "CANCELLED");
        sale("cancelled scheduled", "1 hour", "2 hours", "CANCELLED");

        JsonNode p = page(0);
        assertThat(ids(p)).containsExactly(live, scheduled);
        assertThat(p.get("sales").get(0).get("state").asText()).isEqualTo("LIVE");
        assertThat(p.get("sales").get(1).get("state").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void aSaleThatEndedASecondAgoIsHiddenOnTheDatabaseClock() throws Exception {
        sale("just ended", "-1 hour", "-1 second", "ACTIVE");
        long stillOpen = sale("closing soon", "-1 hour", "1 hour", "ACTIVE");
        assertThat(ids(page(0))).containsExactly(stillOpen);
    }

    // --- what a buyer may learn about stock ---

    @Test
    void itemsCarryInStockOnlyAndNeverAnExactStockNumber() throws Exception {
        long saleId = liveSale("with items");
        item(saleId, 500, 347);
        item(saleId, 500, 0);

        MvcResult r = mockMvc.perform(get("/api/flash-sales")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sales[0].items.length()").value(2)).andReturn();
        String body = r.getResponse().getContentAsString();
        JsonNode items = json.readTree(body).get("sales").get(0).get("items");

        assertThat(items.get(0).get("inStock").asBoolean()).isTrue();
        assertThat(items.get(1).get("inStock").asBoolean()).isFalse();
        for (JsonNode item : items) {
            List<String> fields = new ArrayList<>();
            item.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "productId", "salePrice", "inStock");
            assertThat(item.get("salePrice").isTextual()).as("money is a JSON string").isTrue();
        }
        assertThat(body).doesNotContain("347").doesNotContain("500").doesNotContain("availableStock").doesNotContain("totalStock");
    }

    // --- no N+1 ---

    @Test
    void theStatementCountDoesNotGrowWithTheNumberOfSales() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        long one = liveSale("one");
        item(one, 10, 5);
        long before = stats.getPrepareStatementCount();
        page(0);
        long forOne = stats.getPrepareStatementCount() - before;

        for (int i = 0; i < 9; i++) {
            long s = liveSale("more" + i);
            item(s, 10, 5);
            item(s, 10, 5);
            item(s, 10, 5);
        }
        before = stats.getPrepareStatementCount();
        JsonNode p = page(0);
        long forTen = stats.getPrepareStatementCount() - before;

        assertThat(p.get("sales")).hasSize(10);
        assertThat(forOne).as("clock + sales + items").isEqualTo(3);
        assertThat(forTen).as("same three statements for 10 sales with 28 items").isEqualTo(3);
    }

    // --- bad input ---

    @Test
    void anInvalidPageIsA400InvalidRequest() throws Exception {
        for (String bad : new String[] {"-1", "abc", "1.5", "99999999999", "%20", "1e2"}) {
            mockMvc.perform(get("/api/flash-sales?page=" + bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").isString());
        }
    }

    @Test
    void aMissingPageMeansPageZero() throws Exception {
        liveSale("only");
        assertThat(page(null).get("page").asInt()).isZero();
        assertThat(page(null).get("sales")).hasSize(1);
    }
}
