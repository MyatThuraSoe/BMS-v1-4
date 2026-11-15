package com.bms.service;

import com.bms.entity.InventoryLedger;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.Purchase;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.exception.BusinessException;
import com.bms.repository.InventoryLedgerRepository;
import com.bms.repository.LocationStockRepository;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class InventoryHistoryService {

@Autowired
    private ShopTimeService shopTimeService = new ShopTimeService(null, "UTC");

    private final InventoryLedgerRepository ledgerRepository;
    private final StockMovementRepository movementRepository;
    private final SaleRepository saleRepository;
    private final SaleReturnRepository returnRepository;
    private final PurchaseRepository purchaseRepository;
    private final ProductRepository productRepository;
    private final ProductPriceHistoryRepository priceHistoryRepository;
    private final LocationStockRepository locationStockRepository;
    private final PeriodCloseRepository periodCloseRepository;
    private final LedgerPostingService postingService;
    private final PeriodGuard periodGuard;
    private final AuditLogService auditLogService;

    public InventoryHistoryService(InventoryLedgerRepository ledgerRepository, StockMovementRepository movementRepository,
                                   SaleRepository saleRepository, SaleReturnRepository returnRepository,
                                   PurchaseRepository purchaseRepository, ProductRepository productRepository,
                                   ProductPriceHistoryRepository priceHistoryRepository,
                                   LocationStockRepository locationStockRepository, PeriodCloseRepository periodCloseRepository,
                                   LedgerPostingService postingService, PeriodGuard periodGuard, AuditLogService auditLogService) {
        this.ledgerRepository = ledgerRepository;
        this.movementRepository = movementRepository;
        this.saleRepository = saleRepository;
        this.returnRepository = returnRepository;
        this.purchaseRepository = purchaseRepository;
        this.productRepository = productRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.locationStockRepository = locationStockRepository;
        this.periodCloseRepository = periodCloseRepository;
        this.postingService = postingService;
        this.periodGuard = periodGuard;
        this.auditLogService = auditLogService;
    }

    public Map<String, Object> reconcile() {
        History history = history();
        return result(history);
    }

    @Transactional
    public Map<String, Object> backfill(boolean backupConfirmed, Long userId) {
        if (!backupConfirmed) {
            throw new BusinessException("Confirm a database backup and a successful backfill rehearsal on a copy");
        }
        periodGuard.lock();
        if (periodCloseRepository.count() > 0) {
            throw new BusinessException("Inventory backfill cannot write into closed accounting periods");
        }
        History history = history();
        if (!history.issues().isEmpty()) {
            return Map.of("posted", 0, "blocked", true, "reconciliation", result(history));
        }
        long before = ledgerRepository.count();
        for (InventoryLedger event : history.pending()) {
            event.setCreatedBy(userId);
            postingService.appendHistoricalInventory(event);
        }
        ledgerRepository.flush();
        long posted = ledgerRepository.count() - before;
        auditLogService.logAction(userId, "INVENTORY_LEDGER_BACKFILL", "Inventory history appended without changing stock or cost: " + posted,
                "InventoryLedger", null, null, String.valueOf(posted));
        return Map.of("posted", posted, "blocked", false, "reconciliation", reconcile());
    }

    private Map<String, Object> result(History history) {
        return Map.of("reconciled", history.issues().isEmpty() && history.pending().isEmpty(),
                "products", history.products(), "issues", history.issues(), "missingPostings", history.pending().size(),
                "readOnly", true, "correctionReason", "RECONCILIATION-" + shopTimeService.today(),
                "roundingTolerance", "Value tolerance is 0.0000005 times the sum of replayed owned balances, minimum 0.000001. Historical sale costs are never rewritten.");
    }

    private History history() {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<InventoryLedger> existing = ledgerRepository.findAll(Sort.by("occurredAt", "id"));
        Map<String, InventoryLedger> byIdentity = new HashMap<>();
        for (InventoryLedger row : existing) byIdentity.put(identity(row), row);
        List<InventoryLedger> events = new ArrayList<>();
        List<InventoryLedger> pending = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        Map<Long, Set<String>> flags = new HashMap<>();
        Map<Long, Integer> returned = new HashMap<>();
        List<Product> allProducts = productRepository.findAll(Sort.by("id"));
        Map<String, List<InventoryLedger>> postedPurchaseGroups = new HashMap<>();
        for (InventoryLedger row : existing) {
            if ("PURCHASE".equals(row.getSourceType()) || "PURCHASE_RTV".equals(row.getSourceType())
                    || "PURCHASE_REVERSAL".equals(row.getSourceType())) {
                postedPurchaseGroups.computeIfAbsent(purchaseGroup(row), ignored -> new ArrayList<>()).add(row);
            }
        }
        List<Sale> sales = saleRepository.findAll(Sort.by("saleDate", "id"));
        for (Sale sale : sales) {
            for (SaleItem item : sale.getItems()) {
                addSaleEvent(events, pending, covered, byIdentity, issues, item, sale.getId(), "SALE",
                        String.valueOf(item.getId()), InventoryLedger.EventType.SALE, -item.getQuantity(), sale.getSaleDate());
            }
        }
        for (var saleReturn : returnRepository.findAll(Sort.by("returnDate", "id"))) {
            for (SaleReturnItem item : saleReturn.getItems()) {
                SaleItem saleItem = item.getSaleItem();
                int before = returned.getOrDefault(saleItem.getId(), 0);
                int quantity = item.getQuantityReturned();
                if (quantity <= 0 || before + quantity > saleItem.getQuantity()) {
                    issue(issues, saleItem.getProduct().getId(), "Invalid dated return quantities: " + item.getId());
                    continue;
                }
                if (item.getDisposition() != SaleReturnItem.ReturnDisposition.WRITE_OFF) {
                    addSaleEvent(events, pending, covered, byIdentity, issues, saleItem, saleReturn.getSale().getId(),
                            "SALE_RETURN", saleItem.getId() + ":" + before, InventoryLedger.EventType.RETURN,
                            quantity, saleReturn.getReturnDate());
                }
                returned.put(saleItem.getId(), before + quantity);
            }
        }
        for (Sale sale : sales) {
            for (SaleItem item : sale.getItems()) {
                if (!Objects.equals(item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0,
                        returned.getOrDefault(item.getId(), 0))) {
                    issue(issues, item.getProduct().getId(), "Refund counter lacks matching dated returns: " + item.getId());
                }
                if (Boolean.TRUE.equals(sale.getIsVoided())) {
                    int quantity = item.getQuantity() - returned.getOrDefault(item.getId(), 0);
                    if (sale.getVoidedAt() == null) issue(issues, item.getProduct().getId(), "Void has no recorded date: " + sale.getId());
                    else if (quantity > 0) addSaleEvent(events, pending, covered, byIdentity, issues, item, sale.getId(),
                            "SALE_VOID", String.valueOf(item.getId()), InventoryLedger.EventType.VOID, quantity, sale.getVoidedAt());
                }
            }
        }
        Map<String, List<InventoryLedger>> purchaseMovements = new LinkedHashMap<>();
        Map<Long, Set<Long>> purchaseMovementProducts = new HashMap<>();
        Set<Long> receiptPurchases = new HashSet<>();
        Map<Long, List<StockMovement>> openings = new HashMap<>();
        for (StockMovement movement : movementRepository.findAll(Sort.by("movementDate", "id"))) {
            Long productId = movement.getProduct().getId();
            if (movement.getMovementDate() == null) {
                issue(issues, productId, "Movement lacks a date: " + movement.getId());
                continue;
            }
            StockMovement.ReferenceType ref = movement.getReferenceType();
            if (ref == StockMovement.ReferenceType.SALE || ref == StockMovement.ReferenceType.RETURN
                    || "RETURN_WRITE_OFF".equals(movement.getReasonCode())
                    || movement.getMovementType() == StockMovement.MovementType.TRANSFER_OUT) continue;
            if (ref == StockMovement.ReferenceType.OPENING_BALANCE) {
                openings.computeIfAbsent(productId, key -> new ArrayList<>()).add(movement);
                continue;
            }
            InventoryLedger.EventType type = movementType(movement);
            if (type == null) {
                issue(issues, productId, "Unclassified inventory movement: " + movement.getId());
                continue;
            }
            if (movement.getValuationAmount() == null) {
                issue(issues, productId, "Movement lacks historical valuation: " + movement.getId());
                continue;
            }
            int quantity = signedQuantity(movement);
            BigDecimal value = type == InventoryLedger.EventType.TRANSFER ? cost(BigDecimal.ZERO) : cost(movement.getValuationAmount());
            String source = "STOCK_MOVEMENT";
            Long sourceId = movement.getId();
            String key = type.name().toLowerCase(java.util.Locale.ROOT);
            boolean purchaseEvent = ref == StockMovement.ReferenceType.PURCHASE
                    || ref == StockMovement.ReferenceType.RECEIVING || ref == StockMovement.ReferenceType.RTV;
            if (purchaseEvent) {
                source = type == InventoryLedger.EventType.RECEIPT ? "PURCHASE"
                        : type == InventoryLedger.EventType.RTV ? "PURCHASE_RTV" : "PURCHASE_REVERSAL";
                sourceId = movement.getReferenceId();
                key = "movement:" + movement.getId();
                purchaseMovementProducts.computeIfAbsent(sourceId, ignored -> new HashSet<>()).add(productId);
                if (type == InventoryLedger.EventType.RECEIPT) {
                    receiptPurchases.add(sourceId);
                    if (movement.getLocation() != null && !"SHOP".equalsIgnoreCase(movement.getLocation())) flag(flags, productId, "C1_NON_SHOP_RECEIPT");
                }
                if (type == InventoryLedger.EventType.RTV) flag(flags, productId, "C2_RTV");
            }
            InventoryLedger event = event(productId, type, source, sourceId, key, quantity, value, movement.getMovementDate());
            event.setReason(movement.getDescription());
            if (purchaseEvent) purchaseMovements.computeIfAbsent(purchaseGroup(event), ignored -> new ArrayList<>()).add(event);
            else useEvent(event, events, pending, covered, byIdentity, issues);
        }
        for (var group : purchaseMovements.entrySet()) {
            List<InventoryLedger> posted = postedPurchaseGroups.getOrDefault(group.getKey(), List.of());
            if (posted.isEmpty()) {
                for (InventoryLedger row : group.getValue()) useEvent(row, events, pending, covered, byIdentity, issues);
            } else if (sumQuantity(posted) == sumQuantity(group.getValue()) && sumValue(posted).compareTo(sumValue(group.getValue())) == 0) {
                for (InventoryLedger row : posted) {
                    events.add(row);
                    covered.add(identity(row));
                }
            } else {
                issue(issues, group.getValue().get(0).getProductId(),
                        "Partial or inconsistent purchase ledger coverage; split receipts cannot be guessed: " + group.getKey());
            }
        }
        for (Product product : allProducts) {
            List<StockMovement> sourceOpenings = openings.getOrDefault(product.getId(), List.of());
            InventoryLedger original = byIdentity.get("PRODUCT:" + product.getId() + ":OPENING:" + product.getId() + ":opening");
            if (original != null) {
                if (original.getQtyDelta() != sumMovementQuantity(sourceOpenings)
                        || original.getValueDelta().compareTo(sumMovementValue(sourceOpenings)) != 0) {
                    issue(issues, product.getId(), "Product opening differs from its recorded opening movement");
                }
                events.add(original);
                covered.add(identity(original));
            } else {
                for (StockMovement movement : sourceOpenings) {
                    if (movement.getValuationAmount() == null || movement.getMovementDate() == null) {
                        issue(issues, product.getId(), "Opening stock has no historical valuation/date: " + movement.getId());
                    } else useEvent(event(product.getId(), InventoryLedger.EventType.OPENING, "STOCK_MOVEMENT", movement.getId(),
                            "opening", movement.getQuantity(), movement.getValuationAmount(), movement.getMovementDate()),
                            events, pending, covered, byIdentity, issues);
                }
            }
        }
        for (Purchase purchase : purchaseRepository.findAll(Sort.by("purchaseDate", "id"))) {
            boolean changedOrdered = (purchase.getDeletedAt() != null
                    || (purchase.getUpdatedAt() != null && purchase.getCreatedAt() != null && purchase.getUpdatedAt().isAfter(purchase.getCreatedAt())))
                    && (purchase.getReceivingStatus() == Purchase.ReceivingStatus.ORDERED
                    || purchase.getItems().stream().allMatch(item -> item.getReceivedQuantity() == 0));
            for (var item : purchase.getItems()) {
                if (changedOrdered) flag(flags, item.getProduct().getId(), "C3_CHANGED_UNRECEIVED_PO");
                if ((item.getReceivedQuantity() > 0 || purchase.getReceivingStatus() == Purchase.ReceivingStatus.RECEIVED)
                        && !receiptPurchases.contains(purchase.getId())) {
                    issue(issues, item.getProduct().getId(), "Purchase lacks receipt-dated valuation evidence: " + purchase.getId());
                }
            }
            // Deleted/edited lines may no longer exist on the purchase; surviving
            // receipt movements still identify the products they affected.
            if (changedOrdered) {
                for (Long pid : purchaseMovementProducts.getOrDefault(purchase.getId(), Set.of())) {
                    flag(flags, pid, "C3_CHANGED_UNRECEIVED_PO");
                }
            }
        }
        List<InventoryLedger> seeds = new ArrayList<>();
        for (InventoryLedger row : existing) {
            if (isSeed(row)) {
                seeds.add(row);
                covered.add(identity(row));
                continue;
            }
            if (!covered.contains(identity(row))) issue(issues, row.getProductId(), "Ledger entry lacks matching historical source: " + row.getId());
        }
        // Seeds and their cancellations replay together at the seed timestamp so
        // running snapshots stay correct; the pair nets to zero.
        for (InventoryLedger seed : seeds) {
            events.add(seed);
            InventoryLedger reversal = event(seed.getProductId(), InventoryLedger.EventType.OPENING, "BACKFILL_SEED_REVERSAL",
                    seed.getId(), "seed", -seed.getQtyDelta(), seed.getValueDelta().negate(), seed.getOccurredAt());
            reversal.setReason("Remove transitional seed after complete source-history replay");
            InventoryLedger postedReversal = byIdentity.get(identity(reversal));
            if (postedReversal != null) {
                if (postedReversal.getQtyDelta() != -seed.getQtyDelta()
                        || postedReversal.getValueDelta().compareTo(seed.getValueDelta().negate()) != 0
                        || !postedReversal.getOccurredAt().toLocalDate().equals(seed.getOccurredAt().toLocalDate())) {
                    issue(issues, seed.getProductId(), "Seed reversal amount/date differs from transitional seed: " + seed.getId());
                }
                events.add(postedReversal);
                covered.add(identity(postedReversal));
            } else {
                events.add(reversal);
                pending.add(reversal);
            }
        }
        events.sort((a, b) -> {
            int c = a.getOccurredAt().compareTo(b.getOccurredAt());
            if (c != 0) return c;
            // A seed cancellation must replay immediately after its seed.
            if (isSeed(a) && "BACKFILL_SEED_REVERSAL".equals(b.getSourceType())
                    && Objects.equals(a.getProductId(), b.getProductId())) return -1;
            if ("BACKFILL_SEED_REVERSAL".equals(a.getSourceType()) && isSeed(b)
                    && Objects.equals(a.getProductId(), b.getProductId())) return 1;
            c = Long.compare(a.getSourceId(), b.getSourceId());
            if (c != 0) return c;
            c = a.getLineKey().compareTo(b.getLineKey());
            if (c != 0) return c;
            return a.getSourceType().compareTo(b.getSourceType());
        });
        Map<Long, Balance> balances = new HashMap<>();
        for (InventoryLedger row : events) {
            Balance before = balances.getOrDefault(row.getProductId(), new Balance(0, cost(BigDecimal.ZERO), cost(BigDecimal.ZERO), BigDecimal.ZERO));
            int quantity = before.quantity() + row.getQtyDelta();
            BigDecimal value = before.value().add(row.getValueDelta());
            BigDecimal unitCost = quantity > 0 && value.signum() >= 0
                    ? value.divide(BigDecimal.valueOf(quantity), CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP) : before.unitCost();
            if (row.getEventType() == InventoryLedger.EventType.OPENING && quantity == 0) unitCost = row.getUnitCost();
            if (row.getEventType() == InventoryLedger.EventType.REVALUATION && row.getId() != null) unitCost = row.getUnitCostAfter();
            if (row.getEventType() == InventoryLedger.EventType.REVALUATION && row.getId() == null && quantity == 0) {
                // With no owned units the value delta cannot reveal the new cost;
                // only a dated COST history row is acceptable evidence.
                BigDecimal evidence = revaluationCostEvidence(row.getProductId(), row.getOccurredAt());
                if (evidence != null) unitCost = cost(evidence);
                else issue(issues, row.getProductId(), "Zero-owned revaluation lacks historical cost evidence: " + identity(row));
            }
            if ("BACKFILL_SEED_REVERSAL".equals(row.getSourceType()) && row.getId() != null
                    && (row.getQtyAfter() == null || row.getQtyAfter() != quantity
                        || row.getValueAfter() == null || row.getValueAfter().compareTo(cost(value)) != 0)) {
                issue(issues, row.getProductId(), "Existing seed reversal snapshot differs from replay at its date: " + row.getId());
            }
            if (quantity < 0 || value.signum() < 0) issue(issues, row.getProductId(), "Replay produces negative quantity/value at " + identity(row));
            BigDecimal tolerance = before.tolerance().add(BigDecimal.valueOf(Math.abs((long) quantity)).multiply(new BigDecimal("0.0000005")));
            balances.put(row.getProductId(), new Balance(quantity, cost(value), unitCost, tolerance));
            if (row.getId() == null) {
                row.setQtyAfter(quantity);
                row.setValueAfter(cost(value));
                row.setUnitCostAfter(unitCost);
            }
        }
        List<Map<String, Object>> products = new ArrayList<>();
        for (Product product : allProducts) {
            Balance balance = balances.getOrDefault(product.getId(), new Balance(0, cost(BigDecimal.ZERO), cost(BigDecimal.ZERO), BigDecimal.ZERO));
            long owned = (product.getStockQuantity() != null ? product.getStockQuantity() : 0)
                    + locationStockRepository.sumNonShopQuantityByProduct(product.getId());
            BigDecimal storedValue = cost(product.getCostPrice()).multiply(BigDecimal.valueOf(owned));
            BigDecimal tolerance = balance.tolerance().max(new BigDecimal("0.000001"));
            if (owned != balance.quantity()) issue(issues, product.getId(), "Owned units differ from replay; historical opening or stock events are missing");
            if (storedValue.subtract(balance.value()).abs().compareTo(tolerance) > 0) issue(issues, product.getId(), "Stored inventory value differs from corrected all-owned replay; owner adjustment required");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("productId", product.getId());
            result.put("name", product.getName());
            result.put("ownedUnits", owned);
            result.put("expectedUnits", balance.quantity());
            result.put("storedCost", cost(product.getCostPrice()));
            result.put("expectedCost", balance.unitCost());
            result.put("storedValue", storedValue);
            result.put("expectedValue", balance.value());
            result.put("valueDifference", storedValue.subtract(balance.value()));
            result.put("tolerance", tolerance);
            result.put("flags", flags.getOrDefault(product.getId(), Set.of()));
            products.add(result);
        }
        return new History(pending, issues, products);
    }

    private BigDecimal revaluationCostEvidence(Long productId, LocalDateTime at) {
        List<ProductPriceHistory> matches = priceHistoryRepository
                .findByProductIdOrderByChangedAtDescIdDesc(productId).stream()
                .filter(h -> h.getPriceType() == ProductPriceHistory.PriceType.COST
                        && h.getChangedAt() != null && h.getChangedAt().equals(at) && h.getNewPrice() != null)
                .toList();
        return matches.size() == 1 ? matches.get(0).getNewPrice() : null;
    }

    private static boolean isSeed(InventoryLedger row) {
        return "OPENING_BALANCE".equals(row.getSourceType()) && "seed".equals(row.getLineKey());
    }

    private static void addSaleEvent(List<InventoryLedger> events, List<InventoryLedger> pending, Set<String> covered,
                                     Map<String, InventoryLedger> existing, List<Map<String, Object>> issues, SaleItem item,
                                     Long sourceId, String source, String key, InventoryLedger.EventType type, int quantity, LocalDateTime at) {
        if (at == null || item.getCostPriceAtSale() == null) {
            issue(issues, item.getProduct().getId(), "Sale event lacks original timestamp/cost: " + item.getId());
            return;
        }
        InventoryLedger row = event(item.getProduct().getId(), type, source, sourceId, key, quantity,
                item.getCostPriceAtSale().multiply(BigDecimal.valueOf(quantity)), at);
        row.setUnitCost(cost(item.getCostPriceAtSale()));
        useEvent(row, events, pending, covered, existing, issues);
    }

    private static void useEvent(InventoryLedger event, List<InventoryLedger> events, List<InventoryLedger> pending,
                                 Set<String> covered, Map<String, InventoryLedger> existing, List<Map<String, Object>> issues) {
        InventoryLedger posted = existing.get(identity(event));
        if (posted == null) {
            events.add(event);
            pending.add(event);
        } else {
            if (!posted.getQtyDelta().equals(event.getQtyDelta()) || posted.getValueDelta().compareTo(event.getValueDelta()) != 0
                    || !posted.getOccurredAt().toLocalDate().equals(event.getOccurredAt().toLocalDate())) {
                issue(issues, event.getProductId(), "Source amount/date differs from immutable posting: " + identity(event));
            }
            events.add(posted);
        }
        covered.add(identity(event));
    }

    private static InventoryLedger event(Long productId, InventoryLedger.EventType type, String source, Long sourceId,
                                          String key, int quantity, BigDecimal value, LocalDateTime at) {
        InventoryLedger row = new InventoryLedger();
        row.setProductId(productId);
        row.setEventType(type);
        row.setSourceType(source);
        row.setSourceId(sourceId);
        row.setLineKey(key);
        row.setQtyDelta(quantity);
        row.setValueDelta(cost(value));
        row.setUnitCost(quantity != 0 ? value.divide(BigDecimal.valueOf(quantity), CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP) : cost(BigDecimal.ZERO));
        row.setOccurredAt(at);
        row.setReason("Historical source replay");
        return row;
    }

    private static InventoryLedger.EventType movementType(StockMovement movement) {
        if (movement.getMovementType() == StockMovement.MovementType.REVALUATION) return InventoryLedger.EventType.REVALUATION;
        if (movement.getMovementType() == StockMovement.MovementType.TRANSFER_IN) return InventoryLedger.EventType.TRANSFER;
        return switch (movement.getReferenceType()) {
            case RECEIVING -> InventoryLedger.EventType.RECEIPT;
            case RTV -> InventoryLedger.EventType.RTV;
            case PURCHASE -> movement.getMovementType() == StockMovement.MovementType.IN ? InventoryLedger.EventType.RECEIPT : InventoryLedger.EventType.ADJUSTMENT;
            case STOCK_ADJUSTMENT -> InventoryLedger.EventType.ADJUSTMENT;
            case STOCK_COUNT -> InventoryLedger.EventType.COUNT;
            default -> null;
        };
    }

    private static int signedQuantity(StockMovement movement) {
        return switch (movement.getMovementType()) {
            case REVALUATION, TRANSFER_IN, TRANSFER_OUT -> 0;
            case OUT, ADJUSTMENT_OUT -> -movement.getQuantity();
            case ADJUSTMENT -> movement.getQuantity();
            default -> movement.getQuantity();
        };
    }

    private static String identity(InventoryLedger row) {
        return row.getSourceType() + ":" + row.getSourceId() + ":" + row.getEventType() + ":" + row.getProductId() + ":" + row.getLineKey();
    }

    private static String purchaseGroup(InventoryLedger row) {
        return row.getSourceType() + ":" + row.getSourceId() + ":" + row.getProductId() + ":" + row.getEventType() + ":" + row.getOccurredAt().toLocalDate();
    }

    private static int sumQuantity(List<InventoryLedger> rows) { return rows.stream().mapToInt(InventoryLedger::getQtyDelta).sum(); }
    private static BigDecimal sumValue(List<InventoryLedger> rows) { return rows.stream().map(InventoryLedger::getValueDelta).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static int sumMovementQuantity(List<StockMovement> rows) { return rows.stream().mapToInt(StockMovement::getQuantity).sum(); }
    private static BigDecimal sumMovementValue(List<StockMovement> rows) { return rows.stream().map(StockMovement::getValuationAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static BigDecimal cost(BigDecimal value) { return (value != null ? value : BigDecimal.ZERO).setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP); }
    private static void flag(Map<Long, Set<String>> flags, Long productId, String flag) { flags.computeIfAbsent(productId, ignored -> new HashSet<>()).add(flag); }
    private static void issue(List<Map<String, Object>> issues, Long productId, String reason) { issues.add(Map.of("productId", productId, "reason", reason)); }
    private record Balance(int quantity, BigDecimal value, BigDecimal unitCost, BigDecimal tolerance) {}
    private record History(List<InventoryLedger> pending, List<Map<String, Object>> issues, List<Map<String, Object>> products) {}
}
