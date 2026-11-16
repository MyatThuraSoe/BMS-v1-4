package com.bms.service;

import com.bms.entity.InventoryLedger;
import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Expense;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.StockMovement;
import com.bms.entity.GlMapping;
import com.bms.entity.PaymentMethod;
import com.bms.entity.SaleReturn;
import com.bms.exception.BusinessException;
import com.bms.repository.InventoryLedgerRepository;
import com.bms.repository.LocationStockRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ProfitLedgerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * P1/O8 Stage 2: the single writer of {@link Product#getCostPrice()} and the
 * append-only inventory/profit ledgers. Cost is derived as value_after /
 * qty_after over ALL owned units, preserving the C8 removal guards (keep the
 * last known cost when stock empties or remaining value would go negative).
 *
 * <p>Postings run inside the originating transaction (REQUIRED joins it) and the
 * caller must already hold the product lock. Call postInventory BEFORE mutating
 * stock so a first-time opening balance can be seeded from the pre-event state.
 * Ledger writing is additive: existing stock movements, price history and sale
 * rows are unchanged.
 */
@Service
@Transactional
public class LedgerPostingService {

    @Autowired
    private ShopTimeService shopTimeService;

    private static final int COST_SCALE = CostCalculationUtils.COST_SCALE;
    private static final int MONEY_SCALE = 4;

    /** N1: a settlement split may be off by one rounding cent, no more. */
    private static final BigDecimal SETTLEMENT_TOLERANCE = new BigDecimal("0.01");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private InventoryLedgerRepository inventoryLedgerRepository;

    @Autowired
    private ProfitLedgerRepository profitLedgerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LocationStockRepository locationStockRepository;

    @Autowired
    private InventoryLossService inventoryLossService;

    @Autowired
    private PeriodGuard periodGuard;

    public String periodOf(LocalDateTime at) {
        LocalDateTime effectiveAt = at != null ? at : shopTimeService.now();
        return effectiveAt.format(PERIOD_FMT);
    }

    public InventoryLedger postInventory(Product product, InventoryLedger.EventType eventType,
                                         String sourceType, Long sourceId, String lineKey,
                                         int qtyDelta, BigDecimal unitCost, BigDecimal valueDelta,
                                         Long locationId, String reason, LocalDateTime occurredAt,
                                         Long createdBy) {
        return postInventory(product, eventType, sourceType, sourceId, lineKey, qtyDelta, unitCost,
                valueDelta, locationId, reason, occurredAt, createdBy, null);
    }

    /**
     * Append one valued inventory event and refresh Product.costPrice from the
     * running balance. Idempotent on (sourceType, sourceId, eventType, productId,
     * lineKey): a repeat posting returns the existing row without re-writing.
     */
    public InventoryLedger postInventory(Product product, InventoryLedger.EventType eventType,
                                         String sourceType, Long sourceId, String lineKey,
                                         int qtyDelta, BigDecimal unitCost, BigDecimal valueDelta,
                                         Long locationId, String reason, LocalDateTime occurredAt,
                                         Long createdBy, Consumer<String> alertSink) {
        String key = lineKey != null ? lineKey : "";
        Optional<InventoryLedger> existing = inventoryLedgerRepository
                .findBySourceTypeAndSourceIdAndEventTypeAndProductIdAndLineKey(
                        sourceType, sourceId, eventType, product.getId(), key);
        if (existing.isPresent()) {
            return existing.get();
        }

        BigDecimal unitCostNorm = scaleCost(unitCost);
        BigDecimal valueDeltaNorm = scaleCost(valueDelta);
        LocalDateTime at = occurredAt != null ? occurredAt : shopTimeService.now();
        periodGuard.assertOpen(at);

        // Derive the pre-event balance from the ACTUAL owned stock and cost cache.
        // The caller holds the product lock and posts BEFORE mutating stock, so this
        // is the true pre-event state. It reproduces the scalar weighted average
        // exactly and stays correct even if a historical event was never posted.
        // qty_after / value_after are recorded as actual-before + delta, so a fully
        // posted product chains cleanly for reconciliation.
        int qtyBefore = totalOwnedUnits(product);
        BigDecimal costBefore = eventType == InventoryLedger.EventType.OPENING
                ? unitCostNorm
                : scaleCost(product.getCostPrice());
        BigDecimal valueBefore = scaleCost(costBefore.multiply(BigDecimal.valueOf(qtyBefore)));

        // Seed one opening row for products that pre-date the ledger so the
        // reconciliation sums (sum of qty_delta / value_delta) include opening stock.
        if (eventType != InventoryLedger.EventType.OPENING && qtyBefore > 0
                && !inventoryLedgerRepository.existsByProductId(product.getId())) {
            InventoryLedger seed = new InventoryLedger();
            seed.setProductId(product.getId());
            seed.setEventType(InventoryLedger.EventType.OPENING);
            seed.setSourceType("OPENING_BALANCE");
            seed.setSourceId(product.getId());
            seed.setLineKey("seed");
            seed.setQtyDelta(qtyBefore);
            seed.setUnitCost(costBefore);
            seed.setValueDelta(valueBefore);
            seed.setQtyAfter(qtyBefore);
            seed.setValueAfter(valueBefore);
            seed.setUnitCostAfter(costBefore);
            seed.setReason("Opening balance seeded from current owned stock");
            seed.setOccurredAt(at);
            seed.setCreatedBy(createdBy);
            inventoryLedgerRepository.save(seed);
        }

        int qtyAfter = qtyBefore + qtyDelta;
        BigDecimal valueAfter = scaleCost(valueBefore.add(valueDeltaNorm));

        BigDecimal costAfter;
        if (eventType == InventoryLedger.EventType.REVALUATION) {
            // An explicit revaluation sets the cost regardless of the owned qty.
            costAfter = unitCostNorm;
        } else if (qtyAfter <= 0) {
            costAfter = costBefore;
        } else if (valueAfter.signum() < 0) {
            if (alertSink != null) {
                alertSink.accept("Inventory event drove remaining value negative ("
                        + valueAfter.toPlainString() + ") across " + qtyAfter
                        + " unit(s); kept last known cost " + costBefore.toPlainString());
            }
            costAfter = costBefore;
        } else {
            costAfter = valueAfter.divide(BigDecimal.valueOf(qtyAfter), COST_SCALE, RoundingMode.HALF_UP);
        }

        // Sole writer of the POS cost cache.
        product.setCostPrice(costAfter);
        productRepository.save(product);

        InventoryLedger row = new InventoryLedger();
        row.setProductId(product.getId());
        row.setLocationId(locationId);
        row.setEventType(eventType);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setLineKey(key);
        row.setQtyDelta(qtyDelta);
        row.setUnitCost(unitCostNorm);
        row.setValueDelta(valueDeltaNorm);
        row.setQtyAfter(qtyAfter);
        row.setValueAfter(valueAfter);
        row.setUnitCostAfter(costAfter);
        row.setReason(reason);
        row.setOccurredAt(at);
        row.setCreatedBy(createdBy);
        return inventoryLedgerRepository.save(row);
    }

    public InventoryLedger appendHistoricalInventory(InventoryLedger row) {
        var existing = inventoryLedgerRepository.findBySourceTypeAndSourceIdAndEventTypeAndProductIdAndLineKey(
                row.getSourceType(), row.getSourceId(), row.getEventType(), row.getProductId(), row.getLineKey());
        if (existing.isPresent()) return existing.get();
        periodGuard.assertOpen(row.getOccurredAt());
        return inventoryLedgerRepository.save(row);
    }

    /**
     * Append one profit-affecting event, bucketed into the period of its own
     * occurrence date. Returns and voids post negative rows on their own date so
     * an earlier period is never mutated. Idempotent on the source line key.
     */
    public ProfitLedger postProfit(ProfitLedger.EventType eventType, String sourceType, Long sourceId,
                                   Long productId, String lineKey, Integer quantity,
                                   BigDecimal netRevenue, BigDecimal cogs, BigDecimal expense,
                                   LocalDateTime occurredAt) {
        String key = lineKey != null ? lineKey : "";
        if (profitLedgerRepository.existsPosting(sourceType, sourceId, eventType, productId, key)) {
            return null;
        }
        LocalDateTime at = occurredAt != null ? occurredAt : shopTimeService.now();
        periodGuard.assertOpen(at);
        ProfitLedger row = new ProfitLedger();
        row.setPeriod(periodOf(at));
        row.setEventType(eventType);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setProductId(productId);
        row.setLineKey(key);
        row.setQuantity(quantity);
        row.setNetRevenue(scaleMoney(netRevenue));
        row.setCogs(scaleMoney(cogs));
        row.setExpense(scaleMoney(expense));
        row.setOccurredAt(at);
        return profitLedgerRepository.save(row);
    }

    public void postSaleProfit(Sale sale, SaleItem item) {
        ProfitLedger row = postProfit(ProfitLedger.EventType.SALE, "SALE", sale.getId(), item.getProduct().getId(),
                String.valueOf(item.getId()), item.getQuantity(),
                SaleDiscountUtils.netItemRevenue(sale, item, item.getQuantity()),
                lineCogs(item, item.getQuantity()), BigDecimal.ZERO, sale.getSaleDate());
        if (row != null) {
            BigDecimal tax = SaleDiscountUtils.taxForQuantity(item, item.getQuantity());
            snapshotSaleSettlement(row, tax, sale, sale.getInvoiceNumber());
        }
    }

    /**
     * Posts one returned line. {@code lineDebt} and {@code lineRefund} are that
     * line's own share of the return's debt reduction and refund, never the whole
     * return's totals — the caller splits them per line (N1).
     */
    public void postReturnProfit(SaleReturn saleReturn, SaleItem item, int quantity, int alreadyReturned,
                                 BigDecimal lineDebt, BigDecimal lineRefund) {
        Sale sale = saleReturn.getSale();
        int before = item.getQuantity() - alreadyReturned;
        int after = before - quantity;
        BigDecimal revenue = SaleDiscountUtils.netItemRevenue(sale, item, after)
                .subtract(SaleDiscountUtils.netItemRevenue(sale, item, before));
        BigDecimal cogs = lineCogs(item, after).subtract(lineCogs(item, before));
        ProfitLedger row = postProfit(ProfitLedger.EventType.RETURN, "SALE_RETURN", saleReturn.getId(),
                item.getProduct().getId(), String.valueOf(item.getId()), -quantity, revenue, cogs,
                BigDecimal.ZERO, saleReturn.getReturnDate());
        BigDecimal tax = SaleDiscountUtils.taxForQuantity(item, after)
                .subtract(SaleDiscountUtils.taxForQuantity(item, before));
        snapshotRefundSettlement(row, tax, saleReturn.getRefundMethod(), "RET-" + saleReturn.getId(),
                lineDebt, lineRefund);
    }

    /**
     * N1 repair: recompute only the settlement columns of an existing RETURN row.
     * Revenue, COGS, tax and dates stay frozen; the row is its own source of truth
     * for the line total because those amounts were never wrong.
     */
    public void repairRefundSettlement(ProfitLedger row, PaymentMethod method,
                                       BigDecimal lineDebt, BigDecimal lineRefund) {
        snapshotRefundSettlement(row, row.getTaxAmount(), method, row.getReference(), lineDebt, lineRefund);
    }

    public void postVoidProfit(Sale sale, SaleItem item, int remaining, LocalDateTime voidedAt) {
        ProfitLedger row = postProfit(ProfitLedger.EventType.VOID, "SALE_VOID", sale.getId(), item.getProduct().getId(),
                String.valueOf(item.getId()), -remaining,
                SaleDiscountUtils.netItemRevenue(sale, item, remaining).negate(),
                lineCogs(item, remaining).negate(), BigDecimal.ZERO, voidedAt);
        if (row != null) {
            BigDecimal tax = SaleDiscountUtils.taxForQuantity(item, remaining).negate();
            snapshotSaleSettlement(row, tax, sale, sale.getInvoiceNumber());
        }
    }

    static GlMapping.EventType saleTender(Sale sale) {
        return sale.getSaleType() == Sale.SaleType.CREDIT
                ? GlMapping.EventType.ACCOUNTS_RECEIVABLE : GlMapping.EventType.CASH;
    }

    static GlMapping.EventType refundTender(PaymentMethod method) {
        if (method == null) return GlMapping.EventType.CASH;
        return switch (method) {
            case CASH -> GlMapping.EventType.CASH;
            case CREDIT -> GlMapping.EventType.ACCOUNTS_RECEIVABLE;
            case STORE_CREDIT -> GlMapping.EventType.REFUND_LIABILITY;
            default -> GlMapping.EventType.TENDER_CLEARING;
        };
    }

    static void snapshotSettlement(ProfitLedger row, BigDecimal tax, GlMapping.EventType account, String reference) {
        if (row != null) {
            row.setTaxAmount(scaleMoney(tax));
            row.setSettlementAccount(account);
            row.setReference(reference);
        }
    }

    private void snapshotSaleSettlement(ProfitLedger row, BigDecimal tax, Sale sale, String reference) {
        row.setTaxAmount(scaleMoney(tax));
        row.setReference(reference);
        BigDecimal settlement = row.getNetRevenue().add(row.getTaxAmount());
        row.setSettlementCash(BigDecimal.ZERO.setScale(MONEY_SCALE));
        row.setSettlementClearing(BigDecimal.ZERO.setScale(MONEY_SCALE));
        row.setSettlementAr(BigDecimal.ZERO.setScale(MONEY_SCALE));
        List<SalePayment> payments = sale.getPayments() != null ? sale.getPayments() : List.of();
        if (payments.isEmpty()) {
            setSettlementAmount(row, saleTender(sale), settlement);
            return;
        }
        BigDecimal totalPaid = payments.stream().map(SalePayment::getAmount)
                .filter(Objects::nonNull).map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalPaid.signum() == 0) {
            setSettlementAmount(row, saleTender(sale), settlement);
            return;
        }
        BigDecimal remaining = scaleMoney(settlement);
        for (int i = 0; i < payments.size(); i++) {
            SalePayment payment = payments.get(i);
            BigDecimal allocated = i == payments.size() - 1 ? remaining
                    : scaleMoney(settlement.multiply(money(payment.getAmount()).abs())
                    .divide(totalPaid, MONEY_SCALE + 4, RoundingMode.HALF_UP));
            remaining = remaining.subtract(allocated);
            setSettlementAmount(row, settlementAccount(payment.getPaymentMethod()), allocated);
        }
    }

    /**
     * N1: every settlement part of a RETURN row is stored NEGATIVE, because a
     * refund is always a credit (cash out) or a liability reduction. The GL layer
     * reads these columns as signed deltas, so a positive cash here previously
     * debited the till on a refund. {@code lineDebt} reduces the invoice's AR and
     * {@code lineRefund} is paid out by {@code method}; together they must equal
     * the line's revenue + tax, which is what the guard below enforces.
     */
    private void snapshotRefundSettlement(ProfitLedger row, BigDecimal tax, PaymentMethod method, String reference,
                                        BigDecimal lineDebt, BigDecimal lineRefund) {
        if (row == null) return;
        row.setTaxAmount(scaleMoney(tax));
        row.setReference(reference);

        BigDecimal debt = scaleMoney(lineDebt);
        BigDecimal refund = scaleMoney(lineRefund);

        BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE);
        row.setSettlementCash(zero);
        row.setSettlementClearing(zero);
        row.setSettlementAr(zero);
        row.setSettlementRefundLiability(zero);
        row.setSettlementAccount(null);

        if (debt.signum() != 0) {
            row.setSettlementAr(debt.negate());
        }
        if (refund.signum() != 0) {
            switch (refundTender(method)) {
                case CASH -> row.setSettlementCash(refund.negate());
                case ACCOUNTS_RECEIVABLE -> row.setSettlementAr(money(row.getSettlementAr()).subtract(refund));
                case REFUND_LIABILITY -> row.setSettlementRefundLiability(refund.negate());
                default -> row.setSettlementClearing(refund.negate());
            }
        }

        BigDecimal posted = money(row.getSettlementCash())
                .add(money(row.getSettlementClearing()))
                .add(money(row.getSettlementAr()))
                .add(money(row.getSettlementRefundLiability()));
        BigDecimal lineTotal = money(row.getNetRevenue()).add(money(row.getTaxAmount()));
        if (posted.subtract(lineTotal).abs().compareTo(SETTLEMENT_TOLERANCE) > 0) {
            throw new BusinessException("Refund settlement split of " + posted.toPlainString()
                    + " does not match the returned line total " + lineTotal.toPlainString()
                    + " for " + reference);
        }
    }

    /**
     * N1: the store-credit part carried by a ledger row. Rows written before the
     * settlement_refund_liability column existed kept it implicitly — it is what
     * the other three parts leave of the line total — so reading it through one
     * helper keeps the journal and the reconciliation agreeing on un-repaired
     * history. Returns null when the row has no store-credit part at all.
     */
    public static BigDecimal refundLiabilityOf(ProfitLedger row) {
        if (row.getSettlementRefundLiability() != null) {
            return row.getSettlementRefundLiability();
        }
        boolean legacyStoreCredit = row.getEventType() == ProfitLedger.EventType.RETURN
                && row.getSettlementAccount() == GlMapping.EventType.REFUND_LIABILITY
                && (row.getSettlementCash() != null || row.getSettlementClearing() != null
                || row.getSettlementAr() != null);
        if (!legacyStoreCredit) {
            return null;
        }
        return nz(row.getNetRevenue()).add(nz(row.getTaxAmount()))
                .subtract(nz(row.getSettlementCash()))
                .subtract(nz(row.getSettlementClearing()))
                .subtract(nz(row.getSettlementAr()));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private void setSettlementAmount(ProfitLedger row, GlMapping.EventType account, BigDecimal amount) {
        BigDecimal scaled = scaleMoney(amount);
        row.setSettlementAccount(account);
        if (GlMapping.EventType.CASH == account) {
            row.setSettlementCash(money(row.getSettlementCash()).add(scaled));
        } else if (GlMapping.EventType.ACCOUNTS_RECEIVABLE == account) {
            row.setSettlementAr(money(row.getSettlementAr()).add(scaled));
        } else if (GlMapping.EventType.TENDER_CLEARING == account) {
            row.setSettlementClearing(money(row.getSettlementClearing()).add(scaled));
        }
    }

    private GlMapping.EventType settlementAccount(PaymentMethod method) {
        if (method == null) return GlMapping.EventType.TENDER_CLEARING;
        return switch (method) {
            case CASH -> GlMapping.EventType.CASH;
            case CREDIT -> GlMapping.EventType.ACCOUNTS_RECEIVABLE;
            default -> GlMapping.EventType.TENDER_CLEARING;
        };
    }

    private GlMapping.EventType refundSettlementAccount(PaymentMethod method) {
        if (method == PaymentMethod.CASH) return GlMapping.EventType.CASH;
        if (method == PaymentMethod.CREDIT) return GlMapping.EventType.ACCOUNTS_RECEIVABLE;
        if (method == PaymentMethod.STORE_CREDIT) return GlMapping.EventType.REFUND_LIABILITY;
        return GlMapping.EventType.TENDER_CLEARING;
    }

    private BigDecimal money(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public void postOperatingLoss(StockMovement movement) {
        if (inventoryLossService.isOperatingLoss(movement)) {
            ProfitLedger row = postProfit(ProfitLedger.EventType.WRITE_OFF, "STOCK_MOVEMENT", movement.getId(),
                    movement.getProduct().getId(), inventoryLossService.lossCategory(movement),
                    -movement.getQuantity(), BigDecimal.ZERO, BigDecimal.ZERO,
                    movement.getValuationAmount().abs(), movement.getMovementDate());
            if (row != null) {
                row.setCategory(inventoryLossService.lossCategory(movement));
            }
        }

    }

    public void postInventoryGain(StockMovement movement) {
        BigDecimal value = movement.getValuationAmount();
        if (value == null || value.signum() <= 0) {
            return;
        }
        String reason = movement.getReasonCode() != null
                ? movement.getReasonCode().trim().toUpperCase() : "";
        // S3: a physical count that finds more stock than the books is a gain
        // too, and the counter's reason code is not one of the adjustment ones.
        boolean fromStockCount = movement.getReferenceType() == StockMovement.ReferenceType.STOCK_COUNT;
        if (!fromStockCount && !InventoryLossService.POSITIVE_ADJUSTMENT_REASONS.contains(reason)) {
            return;
        }
        ProfitLedger row = postProfit(ProfitLedger.EventType.INVENTORY_GAIN, "STOCK_MOVEMENT",
                movement.getId(), movement.getProduct().getId(), "inventory-gain",
                movement.getQuantity(), BigDecimal.ZERO, BigDecimal.ZERO, value.negate(),
                movement.getMovementDate());
        if (row != null) {
            row.setCategory(reason.isEmpty() ? "COUNT_CORRECTION" : reason);
            row.setReference("MOV-" + movement.getId());
        }
    }

    public void postExpense(Expense expense) {
        // O4: purchase-capitalised costs already sit in inventory valuation;
        // posting them here would double count them in the P&L.
        if (expense.isCapitalized()) {
            return;
        }
        postExpenseAmount(expense, String.valueOf(expense.getLedgerRevision()), expense.getAmount(),
                expense.getExpenseDate().atStartOfDay());
    }

    public void reverseExpense(Expense expense, LocalDateTime at) {
        // Only non-capitalised expenses ever produced a profit row, so a
        // capitalised one has nothing to reverse (edit/delete lifecycle).
        if (expense.isCapitalized()) {
            return;
        }
        postExpenseAmount(expense, expense.getLedgerRevision() + ":reverse", expense.getAmount().negate(), at);
    }

    private void postExpenseAmount(Expense expense, String key, BigDecimal amount, LocalDateTime at) {
        ProfitLedger.EventType type = expense.getCategory() == Expense.ExpenseCategory.PURCHASE_VARIANCE
                ? ProfitLedger.EventType.PURCHASE_VARIANCE : ProfitLedger.EventType.EXPENSE;
        ProfitLedger row = postProfit(type, "EXPENSE", expense.getId(), null, key, null,
                BigDecimal.ZERO, BigDecimal.ZERO, amount, at);
        if (row != null) {
            row.setCategory(expense.getCategory().name());
            row.setReference("EXP-" + expense.getId());
        }
    }

    private static BigDecimal lineCogs(SaleItem item, int quantity) {
        return scaleMoney(CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(), quantity));
    }

    /** Owned units across every location bucket (shop mirrors Product.stockQuantity). */
    private int totalOwnedUnits(Product product) {
        int shop = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        return shop + (int) locationStockRepository.sumNonShopQuantityByProduct(product.getId());
    }

    private static BigDecimal scaleCost(BigDecimal value) {
        return (value != null ? value : BigDecimal.ZERO).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return (value != null ? value : BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}