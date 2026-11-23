package com.bms.service;

import com.bms.entity.Expense;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.exception.BusinessException;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.ProfitLedgerRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
@Transactional
public class LedgerHistoryService {

    @Autowired
    private ShopTimeService shopTimeService;

    private final SaleRepository saleRepository;
    private final SaleReturnRepository saleReturnRepository;
    private final ExpenseRepository expenseRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProfitLedgerRepository profitLedgerRepository;
    private final PeriodCloseRepository periodCloseRepository;
    private final LedgerPostingService postingService;
    private final InventoryLossService inventoryLossService;
    private final ReportService reportService;
    private final PeriodGuard periodGuard;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public LedgerHistoryService(SaleRepository saleRepository, SaleReturnRepository saleReturnRepository,
                                ExpenseRepository expenseRepository, StockMovementRepository stockMovementRepository,
                                ProfitLedgerRepository profitLedgerRepository, PeriodCloseRepository periodCloseRepository,
                                LedgerPostingService postingService, InventoryLossService inventoryLossService,
                                ReportService reportService, PeriodGuard periodGuard, JdbcTemplate jdbcTemplate,
                                AuditLogService auditLogService) {
        this.saleRepository = saleRepository;
        this.saleReturnRepository = saleReturnRepository;
        this.expenseRepository = expenseRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.profitLedgerRepository = profitLedgerRepository;
        this.periodCloseRepository = periodCloseRepository;
        this.postingService = postingService;
        this.inventoryLossService = inventoryLossService;
        this.reportService = reportService;
        this.periodGuard = periodGuard;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    public Map<String, Object> backfillProfit(boolean backupConfirmed, Long userId) {
        if (!backupConfirmed) {
            throw new BusinessException("Confirm that a database backup was taken and backfill was tested on a copy");
        }
        periodGuard.lock();
        if (periodCloseRepository.count() > 0) {
            throw new BusinessException("Historical backfill cannot write into closed accounting periods");
        }
        History history = history();
        if (!history.issues().isEmpty()) {
            return Map.of("posted", 0, "blocked", true, "issues", history.issues());
        }
        long before = profitLedgerRepository.count();
        for (Event event : history.events()) {
            ProfitLedger posted = postingService.postProfit(event.type(), event.source(), event.sourceId(),
                    event.productId(), event.key(), event.quantity(), event.revenue(), event.cogs(),
                    event.expense(), event.at());
            if (posted != null) {
                posted.setCategory(event.category());
                LedgerPostingService.snapshotSettlement(posted, event.tax(), event.tender(), event.reference());
            }
        }
        profitLedgerRepository.flush();
        long posted = profitLedgerRepository.count() - before;
        auditLogService.logAction(userId, "PROFIT_LEDGER_BACKFILL", "Profit ledger rows appended: " + posted,
                "ProfitLedger", null, null, String.valueOf(posted));
        return Map.of("posted", posted, "blocked", false, "issues", history.issues(),
                "reconciliation", reconcileHistory(history, null, null));
    }

    /**
     * N1 repair: rewrite the settlement columns of the RETURN rows already stored
     * by PR #18-style code. Revenue, COGS, tax and dates are never touched — only
     * the signed split between cash, tender clearing, receivable and refund
     * liability. Repeat-safe: a row whose parts already agree is left alone and
     * not counted. Rows of a return that sits in a closed period are skipped as a
     * group, because handing "the remainder" to the last line only works when all
     * of the lines move together.
     */
    public Map<String, Object> repairReturnSettlements(boolean backupConfirmed, Long userId) {
        if (!backupConfirmed) {
            throw new BusinessException("Confirm that a database backup was taken and "
                    + "the settlement repair was tested on a copy of the database");
        }
        periodGuard.lock();
        Map<Long, List<ProfitLedger>> rowsByReturn = new LinkedHashMap<>();
        for (ProfitLedger row : profitLedgerRepository.findByEventTypeAndSourceTypeOrderByIdAsc(
                ProfitLedger.EventType.RETURN, "SALE_RETURN")) {
            rowsByReturn.computeIfAbsent(row.getSourceId(), key -> new ArrayList<>()).add(row);
        }
        int repaired = 0;
        List<String> skippedPeriods = new ArrayList<>();
        for (Map.Entry<Long, List<ProfitLedger>> group : rowsByReturn.entrySet()) {
            SaleReturn saleReturn = saleReturnRepository.findById(group.getKey()).orElse(null);
            // Only returns written by the PR #18 code carry a refund amount; rows
            // older than that never had a settlement split to repair.
            if (saleReturn == null || saleReturn.getRefundAmount() == null) {
                continue;
            }
            List<String> closed = group.getValue().stream()
                    .map(ProfitLedger::getPeriod)
                    .filter(periodCloseRepository::existsById)
                    .distinct()
                    .toList();
            if (!closed.isEmpty()) {
                closed.stream().filter(period -> !skippedPeriods.contains(period)).forEach(skippedPeriods::add);
                continue;
            }
            repaired += repairSettlementGroup(saleReturn, group.getValue());
        }
        profitLedgerRepository.flush();
        auditLogService.logAction(userId, "RETURN_SETTLEMENT_REPAIR",
                "Return settlement splits rewritten for ledger rows: " + repaired,
                "ProfitLedger", null, null, String.valueOf(repaired));
        return Map.of("repaired", repaired, "skippedClosedPeriods", skippedPeriods,
                "saleReturnsScanned", rowsByReturn.size());
    }

    private int repairSettlementGroup(SaleReturn saleReturn, List<ProfitLedger> rows) {
        BigDecimal debt = money(saleReturn.getDebtReductionAmount());
        BigDecimal total = money(saleReturn.getTotalReturnAmount());
        List<BigDecimal> lineTotals = new ArrayList<>(rows.size());
        for (ProfitLedger row : rows) {
            // The row is its own source of truth for the line total: those amounts
            // were never wrong, only the way the money was split across accounts.
            lineTotals.add(money(row.getNetRevenue()).add(money(row.getTaxAmount())).negate());
        }
        // M5: repair has to split the debt exactly like the return did, otherwise a
        // rewritten row would disagree with the till that booked it.
        List<BigDecimal> lineDebts = SaleReturnService.debtForLines(debt, total, lineTotals);
        int rewritten = 0;
        for (int i = 0; i < rows.size(); i++) {
            ProfitLedger row = rows.get(i);
            BigDecimal lineTotal = lineTotals.get(i);
            BigDecimal lineDebt = lineDebts.get(i);
            BigDecimal[] before = {money(row.getSettlementCash()), money(row.getSettlementClearing()),
                    money(row.getSettlementAr()), money(row.getSettlementRefundLiability())};
            postingService.repairRefundSettlement(row, saleReturn.getRefundMethod(), lineDebt,
                    lineTotal.subtract(lineDebt));
            if (differs(before, row)) {
                rewritten++;
            }
        }
        return rewritten;
    }

    private static boolean differs(BigDecimal[] before, ProfitLedger row) {
        return before[0].compareTo(money(row.getSettlementCash())) != 0
                || before[1].compareTo(money(row.getSettlementClearing())) != 0
                || before[2].compareTo(money(row.getSettlementAr())) != 0
                || before[3].compareTo(money(row.getSettlementRefundLiability())) != 0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reconcile(YearMonth first, YearMonth last) {
        if (first != null && last != null && first.isAfter(last)) {
            throw new BusinessException("Start period must not follow end period");
        }
        return reconcileHistory(history(), first, last);
    }

    private Map<String, Object> reconcileHistory(History history, YearMonth first, YearMonth last) {
        Map<String, BigDecimal[]> expected = new LinkedHashMap<>();
        for (Event event : history.events()) {
            BigDecimal[] totals = expected.computeIfAbsent(YearMonth.from(event.at()).toString(), key -> zeros());
            totals[0] = totals[0].add(event.revenue());
            totals[1] = totals[1].add(event.cogs());
            totals[2] = totals[2].add(event.expense());
        }
        Map<String, BigDecimal[]> actual = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT period, SUM(net_revenue), SUM(cogs), SUM(expense) FROM profit_ledger GROUP BY period",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> actual.put(rs.getString(1),
                        new BigDecimal[]{money(rs.getBigDecimal(2)), money(rs.getBigDecimal(3)), money(rs.getBigDecimal(4))}));
        TreeSet<String> periods = new TreeSet<>(expected.keySet());
        periods.addAll(actual.keySet());
        if (first != null && last != null) {
            for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
                periods.add(month.toString());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>(history.issues());
        validatePostings(history, issues);
        boolean reconciled = issues.isEmpty();
        Map<String, SettlementTotals> settlements = settlementTotalsByPeriod();
        SettlementTotals settlementSummary = new SettlementTotals();
        for (String period : periods) {
            YearMonth month = YearMonth.parse(period);
            if ((first != null && month.isBefore(first)) || (last != null && month.isAfter(last))) continue;
            BigDecimal[] ledger = actual.getOrDefault(period, zeros());
            BigDecimal[] source = expected.getOrDefault(period, zeros());
            Map<String, Object> legacyProfit = reportService.getLegacyProfitSummary(month.atDay(1), month.atEndOfMonth());
            BigDecimal legacyExpense = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE deleted_at IS NULL "
                            + "AND COALESCE(capitalized, 0) = 0 AND purchase_expense_id IS NULL "
                            + "AND expense_date >= ? AND expense_date < ?",
                    BigDecimal.class, month.atDay(1), month.plusMonths(1).atDay(1));
            legacyExpense = legacyExpense.add(inventoryLossService.totalLosses(
                    month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay()));
            BigDecimal[] legacy = {(BigDecimal) legacyProfit.get("revenue"),
                    (BigDecimal) legacyProfit.get("cogs"), legacyExpense};
            BigDecimal[] unexplained = subtract(ledger, source);
            if (unexplained[0].signum() != 0 || unexplained[1].signum() != 0 || unexplained[2].signum() != 0) {
                reconciled = false;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period);
            row.put("ledger", amounts(ledger));
            row.put("sourceEvents", amounts(source));
            row.put("legacy", amounts(legacy));
            row.put("difference", amounts(subtract(ledger, legacy)));
            row.put("explainedDifference", amounts(subtract(source, legacy)));
            row.put("unexplainedDifference", amounts(unexplained));
            SettlementTotals settlement = settlements.getOrDefault(period, new SettlementTotals());
            settlementSummary.add(settlement);
            row.put("settlementExpected", settlement.expected);
            row.put("settlementActual", settlement.actual());
            row.put("settlementDifference", settlement.actual().subtract(settlement.expected));
            row.put("settlementByAccount", settlement.accounts);
            row.put("settlementReconciled", settlement.reconciled());
            if (!settlement.reconciled()) {
                reconciled = false;
            }
            rows.add(row);
        }
        return Map.of("reconciled", reconciled, "months", rows, "issues", issues,
                "settlementReconciliation", settlementSummary.toMap(),
                "timingExplanations", history.explanations(),
                "precisionExplanation", "Profit rows round COGS and losses to 4 decimals; legacy values retain 6 decimals.");
    }

    private Map<String, SettlementTotals> settlementTotalsByPeriod() {
        Map<String, SettlementTotals> result = new LinkedHashMap<>();
        for (ProfitLedger row : profitLedgerRepository.findAll()) {
            if (row.getEventType() != ProfitLedger.EventType.SALE
                    && row.getEventType() != ProfitLedger.EventType.RETURN
                    && row.getEventType() != ProfitLedger.EventType.VOID) {
                continue;
            }
            SettlementTotals totals = result.computeIfAbsent(row.getPeriod(), key -> new SettlementTotals());
            totals.expected = totals.expected.add(money(row.getNetRevenue()).add(money(row.getTaxAmount())));
            if (row.getSettlementCash() != null || row.getSettlementClearing() != null
                    || row.getSettlementAr() != null || row.getSettlementRefundLiability() != null) {
                totals.addAccount("CASH", row.getSettlementCash());
                totals.addAccount("TENDER_CLEARING", row.getSettlementClearing());
                totals.addAccount("ACCOUNTS_RECEIVABLE", row.getSettlementAr());
                totals.addAccount("REFUND_LIABILITY", LedgerPostingService.refundLiabilityOf(row));
            } else if (row.getSettlementAccount() != null) {
                totals.addAccount(row.getSettlementAccount().name(),
                        money(row.getNetRevenue()).add(money(row.getTaxAmount())));
            }
        }
        return result;
    }

    private static final class SettlementTotals {
        private BigDecimal expected = BigDecimal.ZERO;
        private final Map<String, BigDecimal> accounts = new LinkedHashMap<>();

        private void addAccount(String account, BigDecimal amount) {
            if (amount != null && amount.signum() != 0) {
                accounts.merge(account, amount, BigDecimal::add);
            }
        }

        private BigDecimal actual() {
            return accounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private boolean reconciled() {
            return expected.compareTo(actual()) == 0;
        }

        private void add(SettlementTotals other) {
            expected = expected.add(other.expected);
            other.accounts.forEach((account, amount) -> accounts.merge(account, amount, BigDecimal::add));
        }

        private Map<String, Object> toMap() {
            return Map.of("expected", expected, "actual", actual(),
                    "difference", actual().subtract(expected), "byAccount", accounts,
                    "reconciled", reconciled());
        }
    }

    private void validatePostings(History history, List<Map<String, Object>> issues) {
        Map<String, ProfitLedger> postings = new HashMap<>();
        for (ProfitLedger row : profitLedgerRepository.findAll()) postings.put(row.getPostingKey(), row);
        java.util.Set<String> expectedKeys = new java.util.HashSet<>();
        for (Event event : history.events()) {
            String key = event.source() + ":" + event.sourceId() + ":" + event.type() + ":" + event.productId() + ":" + event.key();
            expectedKeys.add(key);
            ProfitLedger row = postings.get(key);
            if (row == null) {
                issue(issues, event.source(), event.sourceId(), "Missing profit source posting: " + event.key());
                continue;
            }
            if (!java.util.Objects.equals(row.getQuantity(), event.quantity())
                    || row.getNetRevenue().compareTo(money(event.revenue())) != 0
                    || row.getCogs().compareTo(money(event.cogs())) != 0
                    || row.getExpense().compareTo(money(event.expense())) != 0
                    || !row.getOccurredAt().equals(event.at())
                    || !row.getPeriod().equals(YearMonth.from(event.at()).toString())
                    || !java.util.Objects.equals(row.getCategory(), event.category())) {
                issue(issues, event.source(), event.sourceId(), "Immutable profit amount/date/category differs from source: " + event.key());
            }
            if (event.type() == ProfitLedger.EventType.SALE || event.type() == ProfitLedger.EventType.RETURN
                    || event.type() == ProfitLedger.EventType.VOID) {
                boolean hasSettlementSplit = row.getSettlementCash() != null
                        || row.getSettlementClearing() != null || row.getSettlementAr() != null
                        || row.getSettlementRefundLiability() != null;
                BigDecimal settlementTotal = hasSettlementSplit
                        ? money(row.getSettlementCash()).add(money(row.getSettlementClearing()))
                                .add(money(row.getSettlementAr()))
                                .add(money(LedgerPostingService.refundLiabilityOf(row)))
                        : money(row.getNetRevenue()).add(money(row.getTaxAmount()));
                boolean tenderMismatch = hasSettlementSplit
                        ? settlementTotal.compareTo(money(event.revenue()).add(money(event.tax()))) != 0
                        : row.getSettlementAccount() != event.tender();
                if (row.getTaxAmount() == null || row.getTaxAmount().compareTo(money(event.tax())) != 0
                        || tenderMismatch || !java.util.Objects.equals(row.getReference(), event.reference())) {
                    issue(issues, event.source(), event.sourceId(), "Missing or inconsistent immutable journal snapshot: " + event.key());
                }
            }
        }
        Map<String, BigDecimal> expectedExpenses = new HashMap<>();
        Map<String, BigDecimal> actualExpenses = new HashMap<>();
        for (Event event : history.events()) {
            if ("EXPENSE".equals(event.source())) {
                String key = event.sourceId() + ":" + YearMonth.from(event.at()) + ":" + event.category();
                expectedExpenses.merge(key, event.expense(), BigDecimal::add);
            }
        }
        for (ProfitLedger row : postings.values()) {
            if ("EXPENSE".equals(row.getSourceType())) {
                String key = row.getSourceId() + ":" + row.getPeriod() + ":" + row.getCategory();
                actualExpenses.merge(key, row.getExpense(), BigDecimal::add);
                if (row.getNetRevenue().signum() != 0 || row.getCogs().signum() != 0
                        || row.getTaxAmount() == null || row.getTaxAmount().signum() != 0) {
                    issue(issues, row.getSourceType(), row.getSourceId(), "Expense posting contains unrelated revenue/COGS/tax");
                }
            }
            if (!expectedKeys.contains(row.getPostingKey()) && !"EXPENSE".equals(row.getSourceType())) {
                issue(issues, row.getSourceType(), row.getSourceId(), "Profit posting lacks a matching historical source");
            }
        }
        java.util.Set<String> expenseKeys = new java.util.HashSet<>(expectedExpenses.keySet());
        expenseKeys.addAll(actualExpenses.keySet());
        for (String key : expenseKeys) {
            if (actualExpenses.getOrDefault(key, BigDecimal.ZERO)
                    .compareTo(expectedExpenses.getOrDefault(key, BigDecimal.ZERO)) != 0) {
                issues.add(Map.of("source", "EXPENSE", "key", key,
                        "reason", "Expense revision/reversal totals differ from the current source category and period"));
            }
        }
    }

    private History history() {
        List<Event> events = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> explanations = new ArrayList<>();
        List<Sale> sales = saleRepository.findAll(Sort.by("saleDate", "id"));
        Map<Long, SaleItem> lines = new HashMap<>();
        Map<Long, Sale> saleById = new HashMap<>();
        Map<Long, Integer> returned = new HashMap<>();
        for (Sale sale : sales) {
            saleById.put(sale.getId(), sale);
            if (sale.getSaleDate() == null) {
                issue(issues, "SALE", sale.getId(), "Missing sale date");
                continue;
            }
            if (!Boolean.TRUE.equals(sale.getIsActive()) || sale.getDeletedAt() != null) {
                explanations.add(Map.of("source", "SALE", "sourceId", sale.getId(),
                        "reason", "Archived sales remain in the immutable ledger but are excluded by the legacy report"));
            }
            for (SaleItem item : sale.getItems()) {
                lines.put(item.getId(), item);
                if (item.getCostPriceAtSale() == null) issue(issues, "SALE_ITEM", item.getId(), "Missing historical cost");
                events.add(new Event(ProfitLedger.EventType.SALE, "SALE", sale.getId(), item.getProduct().getId(),
                        String.valueOf(item.getId()), item.getQuantity(), revenue(sale, item, item.getQuantity()),
                        cogs(item, item.getQuantity()), money(BigDecimal.ZERO), null, sale.getSaleDate(),
                        SaleDiscountUtils.taxForQuantity(item, item.getQuantity()), LedgerPostingService.saleTender(sale),
                        sale.getInvoiceNumber()));
            }
        }
        for (SaleReturn saleReturn : saleReturnRepository.findAll(Sort.by("returnDate", "id"))) {
            if (saleReturn.getReturnDate() == null) {
                issue(issues, "SALE_RETURN", saleReturn.getId(), "Missing return date");
                continue;
            }
            Sale sale = saleById.get(saleReturn.getSale().getId());
            if (sale == null || sale.getSaleDate() == null) {
                issue(issues, "SALE_RETURN", saleReturn.getId(), "Original sale is unavailable");
                continue;
            }
            if (saleReturn.getReturnDate().isBefore(sale.getSaleDate())
                    || (sale.getVoidedAt() != null && saleReturn.getReturnDate().isAfter(sale.getVoidedAt()))) {
                issue(issues, "SALE_RETURN", saleReturn.getId(), "Return date is outside the sale/void lifecycle");
                continue;
            }
            for (SaleReturnItem returnedItem : saleReturn.getItems()) {
                SaleItem item = lines.get(returnedItem.getSaleItem().getId());
                if (item == null) {
                    issue(issues, "SALE_RETURN_ITEM", returnedItem.getId(), "Original sale line is unavailable");
                    continue;
                }
                int beforeReturned = returned.getOrDefault(item.getId(), 0);
                int before = item.getQuantity() - beforeReturned;
                int quantity = returnedItem.getQuantityReturned();
                int after = before - quantity;
                if (quantity <= 0 || after < 0) {
                    issue(issues, "SALE_RETURN_ITEM", returnedItem.getId(), "Return quantity exceeds the original sale");
                    continue;
                }
                BigDecimal netRevenue = revenue(sale, item, after).subtract(revenue(sale, item, before));
                BigDecimal netCogs = cogs(item, after).subtract(cogs(item, before));
                BigDecimal tax = SaleDiscountUtils.taxForQuantity(item, after)
                        .subtract(SaleDiscountUtils.taxForQuantity(item, before));
                BigDecimal expectedRefund = netRevenue.add(tax).negate();
                if (returnedItem.getReturnAmount() == null || expectedRefund.compareTo(returnedItem.getReturnAmount()) != 0) {
                    issue(issues, "SALE_RETURN_ITEM", returnedItem.getId(),
                            "Stored refund differs from cumulative discounted revenue and tax reversal");
                }
                events.add(new Event(ProfitLedger.EventType.RETURN, "SALE_RETURN", saleReturn.getId(), item.getProduct().getId(),
                        String.valueOf(item.getId()), -quantity, netRevenue, netCogs, money(BigDecimal.ZERO),
                        null, saleReturn.getReturnDate(), tax, LedgerPostingService.refundTender(saleReturn.getRefundMethod()),
                        "RET-" + saleReturn.getId()));
                returned.put(item.getId(), beforeReturned + quantity);
                if (!YearMonth.from(sale.getSaleDate()).equals(YearMonth.from(saleReturn.getReturnDate()))) {
                    explanations.add(Map.of("source", "SALE_RETURN", "sourceId", saleReturn.getId(),
                            "salePeriod", YearMonth.from(sale.getSaleDate()).toString(),
                            "eventPeriod", YearMonth.from(saleReturn.getReturnDate()).toString(),
                            "revenue", netRevenue, "cogs", netCogs));
                }
            }
        }
        for (Sale sale : sales) {
            if (sale.getSaleDate() == null) continue;
            for (SaleItem item : sale.getItems()) {
                int actualReturned = returned.getOrDefault(item.getId(), 0);
                int counter = item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
                if (counter != actualReturned) {
                    issue(issues, "SALE_ITEM", item.getId(), "Refund counter differs from dated return records");
                }
                if (Boolean.TRUE.equals(sale.getIsVoided()) && sale.getVoidedAt() != null) {
                    int remaining = item.getQuantity() - actualReturned;
                    if (remaining > 0) events.add(new Event(ProfitLedger.EventType.VOID, "SALE_VOID", sale.getId(),
                            item.getProduct().getId(), String.valueOf(item.getId()), -remaining,
                            revenue(sale, item, remaining).negate(), cogs(item, remaining).negate(),
                            money(BigDecimal.ZERO), null, sale.getVoidedAt(),
                            SaleDiscountUtils.taxForQuantity(item, remaining).negate(), LedgerPostingService.saleTender(sale),
                            sale.getInvoiceNumber()));
                }
            }
            if (Boolean.TRUE.equals(sale.getIsVoided())) {
                if (sale.getVoidedAt() == null) {
                    issue(issues, "SALE", sale.getId(), "Voided sale has no void timestamp; no date was guessed");
                } else {
                    explanations.add(Map.of("source", "SALE_VOID", "sourceId", sale.getId(),
                            "salePeriod", YearMonth.from(sale.getSaleDate()).toString(),
                            "eventPeriod", YearMonth.from(sale.getVoidedAt()).toString(),
                            "reason", "Legacy omits the sale; ledger records the original and dated reversals"));
                }
            }
        }
        for (Expense expense : expenseRepository.findAll(Sort.by("expenseDate", "id"))) {
            if (expense.getDeletedAt() != null) continue;
            // O4: purchase-capitalised costs are inventory, not P&L expense.
            if (expense.isCapitalized()) continue;
            if (expense.getExpenseDate() == null) {
                issue(issues, "EXPENSE", expense.getId(), "Missing expense date");
                continue;
            }
            ProfitLedger.EventType type = expense.getCategory() == Expense.ExpenseCategory.PURCHASE_VARIANCE
                    ? ProfitLedger.EventType.PURCHASE_VARIANCE : ProfitLedger.EventType.EXPENSE;
            events.add(new Event(type, "EXPENSE", expense.getId(), null, String.valueOf(expense.getLedgerRevision()),
                    null, money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(expense.getAmount()),
                    expense.getCategory().name(), expense.getExpenseDate().atStartOfDay()));
        }
        for (StockMovement movement : stockMovementRepository.findAll(Sort.by("movementDate", "id"))) {
            if (movement.getMovementDate() == null) {
                issue(issues, "STOCK_MOVEMENT", movement.getId(), "Missing movement date");
                continue;
            }
            if (inventoryLossService.isOperatingLoss(movement)) {
                String category = inventoryLossService.lossCategory(movement);
                events.add(new Event(ProfitLedger.EventType.WRITE_OFF, "STOCK_MOVEMENT", movement.getId(),
                        movement.getProduct().getId(), category, -movement.getQuantity(), money(BigDecimal.ZERO),
                        money(BigDecimal.ZERO), money(movement.getValuationAmount().abs()), category, movement.getMovementDate()));
            } else if (postingService.isInventoryGain(movement)) {
                events.add(new Event(ProfitLedger.EventType.INVENTORY_GAIN, "STOCK_MOVEMENT", movement.getId(),
                        movement.getProduct().getId(), "inventory-gain", movement.getQuantity(),
                        money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(movement.getValuationAmount()).negate(),
                        postingService.inventoryGainCategory(movement), movement.getMovementDate()));
            } else if (movement.getMovementType() == StockMovement.MovementType.REVALUATION) {
                events.add(new Event(ProfitLedger.EventType.REVALUATION, "STOCK_MOVEMENT", movement.getId(),
                        movement.getProduct().getId(), "revaluation", 0, money(BigDecimal.ZERO), money(BigDecimal.ZERO),
                        money(BigDecimal.ZERO), null, movement.getMovementDate()));
            }
        }
        events.sort(Comparator.comparing(Event::at).thenComparing(Event::source).thenComparing(Event::sourceId)
                .thenComparing(Event::key));
        return new History(events, issues, explanations);
    }

    private static BigDecimal revenue(Sale sale, SaleItem item, int quantity) {
        return money(SaleDiscountUtils.netItemRevenue(sale, item, quantity));
    }

    private static BigDecimal cogs(SaleItem item, int quantity) {
        if (item.getCostPriceAtSale() == null) {
            // The history scan above already records this as a blocking issue.
            // Keep the audit running so the operator sees every problem at once
            // instead of the first exception aborting the report.
            return money(BigDecimal.ZERO);
        }
        return money(CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(), quantity));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value != null ? value : BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO)};
    }

    private static BigDecimal[] subtract(BigDecimal[] left, BigDecimal[] right) {
        return new BigDecimal[]{left[0].subtract(right[0]), left[1].subtract(right[1]), left[2].subtract(right[2])};
    }

    private static Map<String, BigDecimal> amounts(BigDecimal[] values) {
        return Map.of("revenue", values[0], "cogs", values[1], "expense", values[2]);
    }

    private static void issue(List<Map<String, Object>> issues, String source, Long sourceId, String reason) {
        issues.add(Map.of("source", source, "sourceId", sourceId, "reason", reason));
    }

    private record History(List<Event> events, List<Map<String, Object>> issues,
                           List<Map<String, Object>> explanations) {}

    private record Event(ProfitLedger.EventType type, String source, Long sourceId, Long productId, String key,
                         Integer quantity, BigDecimal revenue, BigDecimal cogs, BigDecimal expense,
                         String category, LocalDateTime at, BigDecimal tax,
                         com.bms.entity.GlMapping.EventType tender, String reference) {
        Event(ProfitLedger.EventType type, String source, Long sourceId, Long productId, String key,
              Integer quantity, BigDecimal revenue, BigDecimal cogs, BigDecimal expense,
              String category, LocalDateTime at) {
            this(type, source, sourceId, productId, key, quantity, revenue, cogs, expense, category, at,
                    BigDecimal.ZERO, null, source + "-" + sourceId);
        }
    }
}
