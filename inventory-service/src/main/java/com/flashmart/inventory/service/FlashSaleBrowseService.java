package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.browse.BrowseItemResponse;
import com.flashmart.inventory.dto.browse.BrowsePageResponse;
import com.flashmart.inventory.dto.browse.BrowseSaleResponse;
import com.flashmart.inventory.dto.flashsale.FlashSaleResponse;
import com.flashmart.inventory.entity.FlashSale;
import com.flashmart.inventory.entity.FlashSaleItem;
import com.flashmart.inventory.repository.FlashSaleItemRepository;
import com.flashmart.inventory.repository.FlashSaleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Browse (decisions.md, "Day 8", point 8): uncached, three statements however
 * many sales a page holds: the database clock, the sale page (size + 1 rows),
 * and the items of that page's sales in one query.
 *
 * <p>One read-only transaction, so {@code now()} is the same instant in the
 * clock read and in the page query: the {@code state} shown for a sale agrees
 * with the filter that let it in. A sale ending between two page requests can
 * make the next page skip a row (offset paging, accepted in the decision).
 */
@Service
@RequiredArgsConstructor
public class FlashSaleBrowseService {

    public static final int PAGE_SIZE = 10;

    private final FlashSaleRepository sales;
    private final FlashSaleItemRepository items;

    @Transactional(readOnly = true)
    public BrowsePageResponse page(int page) {
        Instant now = sales.databaseNow();
        List<FlashSale> rows = sales.findBrowsePage(PAGE_SIZE + 1, (long) page * PAGE_SIZE);
        boolean hasNext = rows.size() > PAGE_SIZE;
        List<FlashSale> shown = hasNext ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, List<BrowseItemResponse>> itemsBySale = shown.isEmpty() ? Map.of()
                : items.findByFlashSaleIdInOrderByIdAsc(shown.stream().map(FlashSale::getId).toList()).stream()
                        .collect(Collectors.groupingBy(FlashSaleItem::getFlashSaleId,
                                Collectors.mapping(BrowseItemResponse::from, Collectors.toList())));

        List<BrowseSaleResponse> body = shown.stream()
                .map(s -> BrowseSaleResponse.of(FlashSaleResponse.from(s, now),
                        itemsBySale.getOrDefault(s.getId(), List.of())))
                .toList();
        return new BrowsePageResponse(page, PAGE_SIZE, hasNext, body);
    }
}
