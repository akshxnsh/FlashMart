package com.flashmart.inventory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.flashmart.inventory.dto.browse.BrowsePageResponse;
import com.flashmart.inventory.service.FlashSaleBrowseService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public browse, routed through the Gateway (so it needs a Bearer token there).
 * {@code page} is 0-based; absent means 0; negative or not a whole number is a
 * 400 {@code INVALID_REQUEST}. The page size is fixed at 10.
 */
@Tag(name = "Flash sales")
@RestController
@RequiredArgsConstructor
public class FlashSaleController {

    private final FlashSaleBrowseService browse;

    @Operation(summary = "Browse flash sales, 10 per page.")
    @GetMapping("/api/flash-sales")
    public BrowsePageResponse list(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page) {
        return browse.page(page);
    }
}
