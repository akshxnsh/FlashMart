package com.flashmart.inventory.dto.browse;

import java.util.List;

/**
 * One page of {@code GET /api/flash-sales}. {@code hasNext} comes from asking
 * the database for one row more than the page size, so there is no COUNT(*).
 */
public record BrowsePageResponse(int page, int size, boolean hasNext, List<BrowseSaleResponse> sales) {
}
