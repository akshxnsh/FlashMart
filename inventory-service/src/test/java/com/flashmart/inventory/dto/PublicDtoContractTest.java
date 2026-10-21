package com.flashmart.inventory.dto;

import com.flashmart.inventory.controller.ReservationController;
import com.flashmart.inventory.dto.flashsale.AddSaleItemRequest;
import com.flashmart.inventory.dto.flashsale.CreateFlashSaleRequest;
import com.flashmart.inventory.dto.flashsale.FlashSaleItemResponse;
import com.flashmart.inventory.dto.flashsale.UpdateFlashSaleRequest;
import com.flashmart.inventory.entity.FlashSaleItem;
import com.flashmart.inventory.service.ReservationService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public DTOs have no controller yet, so this uses the application's own
 * Validator and ObjectMapper beans (borrowed from the web slice, the same
 * beans a controller would use), not hand-built ones.
 */
@WebMvcTest(ReservationController.class)
class PublicDtoContractTest {

    private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant END = Instant.parse("2030-01-01T12:00:00Z");

    @Autowired
    private Validator validator;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationService reservationService;

    private static Set<String> invalidFields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    // --- sale window ---

    @Test
    void createRejectsEndBeforeStartAndEndEqualToStart() {
        assertEquals(Set.of("windowOrdered"),
                invalidFields(validator.validate(new CreateFlashSaleRequest("Drop", END, START))));
        assertEquals(Set.of("windowOrdered"),
                invalidFields(validator.validate(new CreateFlashSaleRequest("Drop", START, START))));
        assertTrue(validator.validate(new CreateFlashSaleRequest("Drop", START, END)).isEmpty());
    }

    @Test
    void updateRejectsEndBeforeStartAndEndEqualToStart() {
        assertEquals(Set.of("windowOrdered"),
                invalidFields(validator.validate(new UpdateFlashSaleRequest("Drop", END, START))));
        assertEquals(Set.of("windowOrdered"),
                invalidFields(validator.validate(new UpdateFlashSaleRequest("Drop", START, START))));
        assertTrue(validator.validate(new UpdateFlashSaleRequest("Drop", START, END)).isEmpty());
    }

    @Test
    void missingTimeIsReportedAsMissingNotAsABadWindow() {
        assertEquals(Set.of("endTime"),
                invalidFields(validator.validate(new CreateFlashSaleRequest("Drop", START, null))));
    }

    @Test
    void blankSaleNameIsRejected() {
        assertEquals(Set.of("name"),
                invalidFields(validator.validate(new CreateFlashSaleRequest("   ", START, END))));
    }

    // --- sale price ---

    @Test
    void salePriceAllowsZeroButNotNegativeOrAThirdDecimal() {
        assertTrue(validator.validate(new AddSaleItemRequest(1L, new BigDecimal("0.00"), 5)).isEmpty());
        assertEquals(Set.of("salePrice"),
                invalidFields(validator.validate(new AddSaleItemRequest(1L, new BigDecimal("-0.01"), 5))));
        assertEquals(Set.of("salePrice"),
                invalidFields(validator.validate(new AddSaleItemRequest(1L, new BigDecimal("1.001"), 5))));
    }

    @Test
    void addSaleItemAcceptsSalePriceAsStringOrNumber() {
        AddSaleItemRequest fromString = objectMapper.readValue(
                "{\"productId\": 1, \"salePrice\": \"3999.00\", \"totalStock\": 5}", AddSaleItemRequest.class);
        AddSaleItemRequest fromNumber = objectMapper.readValue(
                "{\"productId\": 1, \"salePrice\": 3999.00, \"totalStock\": 5}", AddSaleItemRequest.class);

        assertEquals(new BigDecimal("3999.00"), fromString.salePrice());
        assertEquals(new BigDecimal("3999.00"), fromNumber.salePrice());
    }

    // --- sale item response ---

    @Test
    void saleItemResponseHasSalePriceAsTwoDecimalStringAndNoStock() {
        FlashSaleItem item = new FlashSaleItem(11L, 7L, 501L, new BigDecimal("3999"), 500, 120, 4L);

        JsonNode json = objectMapper.valueToTree(FlashSaleItemResponse.from(item));

        assertTrue(json.get("salePrice").isString());
        assertEquals("3999.00", json.get("salePrice").asString());
        assertFalse(json.has("availableStock"));
        assertFalse(json.has("version"));
    }

    @Test
    void saleItemResponseNeverUsesScientificNotation() {
        FlashSaleItem item = new FlashSaleItem(11L, 7L, 501L, new BigDecimal("1E+3"), 500, 120, 4L);

        assertEquals("1000.00", objectMapper.valueToTree(FlashSaleItemResponse.from(item)).get("salePrice").asString());
    }
}
