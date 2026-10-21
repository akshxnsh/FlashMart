package com.flashmart.inventory.controller;

import com.flashmart.inventory.dto.reserve.ReserveOutcome;
import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.exception.QuantityMismatchException;
import com.flashmart.inventory.service.ReservationService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reserve HTTP contract, through the real Jackson configuration, the real
 * validator and the real exception handler. The service is a mock: this
 * proves the contract's shape, not reserve's logic, evaluation order, replay
 * or concurrency, none of which exists yet.
 */
@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final String URL = "/internal/flash-sale-items/reserve";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    private ResultActions reserve(String json) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String orderId, String quantity) {
        return "{\"orderId\": " + orderId + ", \"flashSaleId\": 7, \"productId\": 501, \"quantity\": " + quantity + "}";
    }

    // --- 200: the response shape ---

    @Test
    void reservedReturnsSalePriceAsAJsonString() throws Exception {
        given(reservationService.reserve(any()))
                .willReturn(ReserveResponse.reserved(900L, new BigDecimal("3999.00"), 3L, "Puma RS-X"));

        reserve(body("42", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESERVED"))
                .andExpect(jsonPath("$.reservationId").value(900))
                .andExpect(jsonPath("$.salePrice").isString())
                .andExpect(jsonPath("$.salePrice").value("3999.00"))
                .andExpect(jsonPath("$.brandId").value(3))
                .andExpect(jsonPath("$.productName").value("Puma RS-X"));
    }

    @Test
    void salePriceAlwaysHasTwoDecimalsInPlainNotation() throws Exception {
        given(reservationService.reserve(any()))
                .willReturn(ReserveResponse.reserved(900L, new BigDecimal("3999"), 3L, "Puma RS-X"));
        reserve(body("42", "2")).andExpect(jsonPath("$.salePrice").value("3999.00"));

        // 1E+3 is a valid BigDecimal whose toString() is scientific notation.
        given(reservationService.reserve(any()))
                .willReturn(ReserveResponse.reserved(900L, new BigDecimal("1E+3"), 3L, "Puma RS-X"));
        reserve(body("42", "2")).andExpect(jsonPath("$.salePrice").value("1000.00"));
    }

    /**
     * The body must be exactly {"outcome": ...}. Spring's doesNotExist() also
     * passes for a key that is present with a null value, so it cannot prove
     * the fields are omitted; a map of size 1 can.
     */
    @ParameterizedTest
    @EnumSource(value = ReserveOutcome.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
    void nonReservedOutcomesCarryOnlyTheOutcome(ReserveOutcome outcome) throws Exception {
        given(reservationService.reserve(any())).willReturn(ReserveResponse.notReserved(outcome));

        reserve(body("42", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$", hasEntry("outcome", outcome.name())));
    }

    // --- 400: quantity ---

    @Test
    void zeroQuantityIsInvalidQuantityAndNeverReachesTheService() throws Exception {
        reserve(body("42", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void fractionalQuantityIsInvalidQuantityNotTruncated() throws Exception {
        reserve(body("42", "2.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void quantityAtTheCapReachesTheService() throws Exception {
        given(reservationService.reserve(any())).willReturn(ReserveResponse.notReserved(ReserveOutcome.OUT_OF_STOCK));

        reserve(body("42", String.valueOf(ReserveRequest.MAX_QUANTITY_PER_ITEM))).andExpect(status().isOk());

        verify(reservationService).reserve(new ReserveRequest(42L, 7L, 501L, ReserveRequest.MAX_QUANTITY_PER_ITEM));
    }

    @Test
    void quantityAboveTheCapIsInvalidQuantityAndNeverReachesTheService() throws Exception {
        reserve(body("42", String.valueOf(ReserveRequest.MAX_QUANTITY_PER_ITEM + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void quantityWinsWhenAnotherFieldIsAlsoInvalid() throws Exception {
        reserve("{\"flashSaleId\": 7, \"productId\": 501, \"quantity\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
    }

    @Test
    void quantityAsANumericStringIsAccepted() throws Exception {
        given(reservationService.reserve(any())).willReturn(ReserveResponse.notReserved(ReserveOutcome.OUT_OF_STOCK));

        reserve(body("42", "\"2\"")).andExpect(status().isOk());

        verify(reservationService).reserve(new ReserveRequest(42L, 7L, 501L, 2));
    }

    // --- 400: any other field ---

    @Test
    void missingOrderIdIsInvalidRequest() throws Exception {
        reserve("{\"flashSaleId\": 7, \"productId\": 501, \"quantity\": 2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void fractionalOrderIdIsInvalidRequest() throws Exception {
        reserve(body("4.5", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void malformedJsonIsInvalidRequest() throws Exception {
        reserve("{\"orderId\": 42,")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- what the reserve handler deliberately does NOT answer ---

    /**
     * The placeholder service throws UnsupportedOperationException. It must
     * stay unmapped, so it can never come back as an ErrorResponse or a
     * business outcome. MockMvc has no error page, so an unmapped exception
     * surfaces here as a thrown ServletException. If anyone maps it, or a
     * parent such as RuntimeException, a response comes back instead and
     * this test fails.
     */
    @Test
    void unsupportedOperationIsNotMappedByTheReserveHandler() {
        given(reservationService.reserve(any())).willThrow(new UnsupportedOperationException("not built"));

        ServletException thrown = assertThrows(ServletException.class, () -> reserve(body("42", "2")));

        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }

    /**
     * 405 is raised while Spring is still choosing a handler, before any
     * controller is selected, so the controller-scoped advice cannot see it.
     * Its body is Spring's default, not { code, message }.
     */
    @Test
    void getOnTheReserveUrlIs405WithSpringsDefaultBody() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(not(containsString("\"code\""))));

        verifyNoInteractions(reservationService);
    }

    /**
     * Unlike 405, this 415 IS visible to the scoped advice: the mapping
     * declares no consumes, so the controller is chosen first and the 415 is
     * raised when no converter can read text/plain into ReserveRequest. It is
     * mapped: status stays 415, body is { code: INVALID_REQUEST, message }.
     * Fails if the handler is removed (the body would be Spring's default).
     */
    @Test
    void textPlainBodyIs415WithInvalidRequestCode() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content(body("42", "2")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(reservationService);
    }

    // --- 409 ---

    @Test
    void quantityMismatchIs409() throws Exception {
        given(reservationService.reserve(any())).willThrow(new QuantityMismatchException(42L, 2, 3));

        reserve(body("42", "3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_MISMATCH"));
    }
}
