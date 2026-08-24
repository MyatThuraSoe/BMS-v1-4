package com.bms.service;

import com.bms.dto.response.InventorySummaryResponse;
import com.bms.dto.response.MovementStatsResponse;
import com.bms.dto.response.StockMovementResponse;
import com.bms.entity.Product;
import com.bms.entity.StockMovement;
import com.bms.repository.ProductRepository;
import com.bms.repository.StockMovementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    // -----------------------------------------------------------------------
    // Summary — headline counts, stock valuation, category breakdown,
    // low-stock watchlist
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InventorySummaryResponse getSummary() {
        InventorySummaryResponse summary = new InventorySummaryResponse();

        Object[] statusCounts = firstRow(productRepository.getStockStatusCounts(), 4);
        long totalProducts = toLong(statusCounts[0]);
        summary.setActiveProducts(totalProducts);
        summary.setInStockCount(toLong(statusCounts[1]));
        summary.setLowStockCount(toLong(statusCounts[2]));
        summary.setOutOfStockCount(toLong(statusCounts[3]));

        Object[] values = firstRow(productRepository.getInventoryValues(), 3);
        long totalUnits = toLong(values[0]);
        BigDecimal costValue = toBigDecimal(values[1]);
        BigDecimal retailValue = toBigDecimal(values[2]);

        summary.setTotalUnits(totalUnits);
        summary.setCostValue(costValue);
        summary.setRetailValue(retailValue);
        summary.setPotentialProfit(retailValue.subtract(costValue).max(BigDecimal.ZERO));

        // Inactive products = all rows minus active rows (includes soft-deleted)
        long inactiveEstimate = Math.max(0, productRepository.count() - totalProducts);
        summary.setInactiveProducts(inactiveEstimate);
        summary.setTotalProducts(productRepository.count());

        for (Object[] row : productRepository.getCategoryInventoryBreakdown()) {
            InventorySummaryResponse.CategoryBreakdown cat = new InventorySummaryResponse.CategoryBreakdown();
            cat.setCategoryId(row[0] != null ? ((Number) row[0]).longValue() : null);
            cat.setCategoryName(row[1] != null ? row[1].toString() : "—");
            cat.setProductCount(row[2] != null ? ((Number) row[2]).longValue() : 0);
            cat.setUnits(row[3] != null ? ((Number) row[3]).longValue() : 0);
            cat.setCostValue(toBigDecimal(row[4]));
            cat.setRetailValue(toBigDecimal(row[5]));
            summary.getCategoryBreakdown().add(cat);
        }

        Pageable top50 = PageRequest.of(0, 50);
        for (Object[] row : productRepository.findLowStockRows(top50)) {
            InventorySummaryResponse.LowStockItem item = new InventorySummaryResponse.LowStockItem();
            item.setProductId(((Number) row[0]).longValue());
            item.setProductName(row[1] != null ? row[1].toString() : "");
            item.setSku(row[2] != null ? row[2].toString() : "");
            item.setCategoryName(row[3] != null ? row[3].toString() : null);
            int stock = row[4] != null ? ((Number) row[4]).intValue() : 0;
            int minStock = row[5] != null ? ((Number) row[5]).intValue() : 0;
            item.setStockQuantity(stock);
            item.setMinStockLevel(minStock);
            item.setShortage(Math.max(0, minStock - stock));
            summary.getLowStockItems().add(item);
        }

        return summary;
    }

    // -----------------------------------------------------------------------
    // Movement ledger — filtered, paged, newest first
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> getMovements(Long productId, String movementType,
                                                    String search, LocalDateTime dateFrom,
                                                    LocalDateTime dateTo, Pageable pageable) {
        StockMovement.MovementType type = null;
        if (movementType != null && !movementType.isBlank()) {
            type = StockMovement.MovementType.valueOf(movementType.trim().toUpperCase());
        }
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<StockMovement> page = stockMovementRepository.findFiltered(
                productId, type, normalizedSearch, dateFrom, dateTo, pageable);

        return page.map(this::convertToResponse);
    }

    // -----------------------------------------------------------------------
    // Movement statistics — daily IN/OUT trend + cause mix over N days
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MovementStatsResponse getMovementStats(int days) {
        int window = Math.min(Math.max(days, 1), 365);
        LocalDateTime from = LocalDate.now().minusDays(window - 1L).atStartOfDay();

        MovementStatsResponse stats = new MovementStatsResponse();
        stats.setDays(window);

        // Aggregate in Java — the app runs on H2 (Electron) and MySQL, and
        // date-truncation SQL differs between them. Row volume over the
        // window is small at POS scale.
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        Map<String, long[]> byDay = new LinkedHashMap<>();
        long sumIn = 0;
        long sumOut = 0;
        Map<String, long[]> byReference = new HashMap<>();

        for (Object[] row : stockMovementRepository.findMovementsSince(from)) {
            StockMovement.MovementType type = (StockMovement.MovementType) row[0];
            int qty = row[1] != null ? ((Number) row[1]).intValue() : 0;
            LocalDateTime when = toLocalDateTime(row[2]);
            String refType = row[3] != null ? ((StockMovement.ReferenceType) row[3]).name() : "UNKNOWN";

            boolean isIn = type == StockMovement.MovementType.IN
                    || type == StockMovement.MovementType.ADJUSTMENT_IN;

            String dayKey = when.toLocalDate().format(fmt);
            long[] day = byDay.computeIfAbsent(dayKey, k -> new long[2]);
            if (isIn) {
                day[0] += qty;
                sumIn += qty;
            } else {
                day[1] += qty;
                sumOut += qty;
            }

            long[] refAgg = byReference.computeIfAbsent(refType, k -> new long[2]);
            refAgg[0] += 1;
            refAgg[1] += qty;
        }

        for (Map.Entry<String, long[]> e : byDay.entrySet()) {
            MovementStatsResponse.DailyMovement daily = new MovementStatsResponse.DailyMovement();
            daily.setDate(e.getKey());
            daily.setInQty(e.getValue()[0]);
            daily.setOutQty(e.getValue()[1]);
            stats.getDaily().add(daily);
        }
        stats.setTotalIn(sumIn);
        stats.setTotalOut(sumOut);
        stats.setNetChange(sumIn - sumOut);

        stats.getByReference().addAll(byReference.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> {
                    MovementStatsResponse.ReferenceCount ref = new MovementStatsResponse.ReferenceCount();
                    ref.setReferenceType(e.getKey());
                    ref.setCount(e.getValue()[0]);
                    ref.setQuantity(e.getValue()[1]);
                    return ref;
                })
                .collect(java.util.stream.Collectors.toList()));

        return stats;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
        if (value instanceof java.util.Date) {
            return LocalDateTime.ofInstant(((java.util.Date) value).toInstant(),
                    java.time.ZoneId.systemDefault());
        }
        throw new IllegalArgumentException("Unexpected date type: " + value);
    }

    private StockMovementResponse convertToResponse(StockMovement sm) {
        StockMovementResponse response = new StockMovementResponse();
        response.setId(sm.getId());
        if (sm.getProduct() != null) {
            response.setProductId(sm.getProduct().getId());
            response.setProductName(sm.getProduct().getName());
            response.setSku(sm.getProduct().getSku());
        }
        response.setMovementType(sm.getMovementType() != null ? sm.getMovementType().name() : null);
        response.setQuantity(sm.getQuantity());
        response.setReferenceType(sm.getReferenceType() != null ? sm.getReferenceType().name() : null);
        response.setReferenceId(sm.getReferenceId());
        response.setDescription(sm.getDescription());
        response.setCreatedByName(sm.getCreatedBy() != null ? sm.getCreatedBy().getUsername() : null);
        response.setMovementDate(sm.getMovementDate());
        return response;
    }

    private Object[] firstRow(List<Object[]> rows, int columns) {
        if (rows == null || rows.isEmpty()) {
            return new Object[columns];
        }
        return rows.get(0);
    }

    private long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal ? (BigDecimal) value
                : value instanceof Number ? BigDecimal.valueOf(((Number) value).doubleValue())
                : BigDecimal.ZERO;
    }
}
