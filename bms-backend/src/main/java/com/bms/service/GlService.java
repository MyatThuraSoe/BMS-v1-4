package com.bms.service;

import com.bms.dto.request.GlAccountRequest;
import com.bms.dto.request.GlMappingRequest;
import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class GlService {

    @Autowired
    private ShopTimeService shopTimeService;

    private final GlAccountRepository accountRepository;
    private final GlMappingRepository mappingRepository;
    private final PurchaseRepository purchaseRepository;
    private final SaleRepository saleRepository;
    private final StockMovementRepository stockMovementRepository;
    private final InventoryLossService inventoryLossService;
    private final ExpenseRepository expenseRepository;
    private final ArPaymentRepository arPaymentRepository;
    private final SaleReturnRepository saleReturnRepository;
    private final CashDrawerMovementRepository cashDrawerMovementRepository;

    @Autowired
    private LedgerReadService ledgerReadService;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    private static final String LEGACY_KEY = LedgerReadService.LEGACY_ENABLED_KEY;

    /** True when the legacy (pre-cutover) GL and report path is explicitly enabled. */
    private boolean legacyEnabled() {
        return systemSettingRepository.findBySettingKey(LEGACY_KEY)
                .map(setting -> "true".equalsIgnoreCase(setting.getSettingValue()))
                .orElse(false);
    }

    @Autowired
    private ProfitLedgerRepository profitLedgerRepository;

    @Autowired
    private InventoryLedgerRepository inventoryLedgerRepository;

    @Autowired
    private PurchasePaymentRepository purchasePaymentRepository;

    public GlService(GlAccountRepository accountRepository,
                     GlMappingRepository mappingRepository,
                     PurchaseRepository purchaseRepository,
                     SaleRepository saleRepository,
                     StockMovementRepository stockMovementRepository,
                     InventoryLossService inventoryLossService,
                     ExpenseRepository expenseRepository,
                     ArPaymentRepository arPaymentRepository,
                     SaleReturnRepository saleReturnRepository,
                     CashDrawerMovementRepository cashDrawerMovementRepository) {
        this.accountRepository = accountRepository;
        this.mappingRepository = mappingRepository;
        this.purchaseRepository = purchaseRepository;
        this.saleRepository = saleRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.inventoryLossService = inventoryLossService;
        this.expenseRepository = expenseRepository;
        this.arPaymentRepository = arPaymentRepository;
        this.saleReturnRepository = saleReturnRepository;
        this.cashDrawerMovementRepository = cashDrawerMovementRepository;
    }

    // ------------------------------------------------------------------
    // Chart of accounts / mappings
    // ------------------------------------------------------------------

    public List<GlAccount> listAccounts() {
        ensureDefaults();
        return accountRepository.findAllByOrderByCodeAsc();
    }

    public GlAccount createAccount(GlAccountRequest request) {
        if (accountRepository.findByCode(request.getCode().trim()).isPresent()) {
            throw new BusinessException("A GL account with this code already exists");
        }
        GlAccount account = new GlAccount();
        apply(account, request);
        return accountRepository.save(account);
    }

    public GlAccount updateAccount(Long id, GlAccountRequest request) {
        GlAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GL account not found: " + id));
        apply(account, request);
        return accountRepository.save(account);
    }

    private void apply(GlAccount account, GlAccountRequest request) {
        account.setCode(request.getCode().trim());
        account.setName(request.getName().trim());
        try {
            account.setType(GlAccount.AccountType.valueOf(request.getType().trim().toUpperCase()));
        } catch (Exception ex) {
            throw new BusinessException("Invalid GL account type: " + request.getType());
        }
        account.setIsActive(request.getActive() == null || request.getActive());
    }

    public List<GlMapping> listMappings() {
        ensureDefaults();
        return mappingRepository.findAllByOrderByEventTypeAsc();
    }

    public GlMapping upsertMapping(GlMappingRequest request) {
        GlMapping.EventType eventType;
        try {
            eventType = GlMapping.EventType.valueOf(request.getEventType().trim().toUpperCase());
        } catch (Exception ex) {
            throw new BusinessException("Invalid GL event type: " + request.getEventType());
        }
        GlAccount account = accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "GL account not found: " + request.getAccountId()));
        GlMapping mapping = mappingRepository.findByEventType(eventType).orElseGet(GlMapping::new);
        mapping.setEventType(eventType);
        mapping.setAccount(account);
        mapping.setDescription(request.getDescription());
        return mappingRepository.save(mapping);
    }

    public void deleteMapping(Long id) {
        GlMapping mapping = mappingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GL mapping not found: " + id));
        mappingRepository.delete(mapping);
    }

    /**
     * Seeds the default chart of accounts and event mappings idempotently. Each
     * account/mapping is created only when missing, so existing installations
     * gain newly added event classes without overwriting custom accounts or
     * user-edited mappings.
     */
    public void ensureDefaults() {
        GlAccount cash = account("1000", "Cash", GlAccount.AccountType.ASSET);
        GlAccount receivable = account("1100", "Accounts Receivable", GlAccount.AccountType.ASSET);
        GlAccount inventory = account("1200", "Inventory Asset", GlAccount.AccountType.ASSET);
        GlAccount tenderClearing = account("1400", "Tender Clearing", GlAccount.AccountType.ASSET);
        GlAccount cashClearing = account("1500", "Cash Clearing", GlAccount.AccountType.ASSET);
        GlAccount payable = account("2000", "Accounts Payable", GlAccount.AccountType.LIABILITY);
        GlAccount taxPayable = account("2100", "Sales Tax Payable", GlAccount.AccountType.LIABILITY);
        GlAccount expensePayable = account("2200", "Expense Payable", GlAccount.AccountType.LIABILITY);
        GlAccount refundLiability = account("2300", "Customer Refund Liability", GlAccount.AccountType.LIABILITY);
        GlAccount inventoryAdjustment = account("3100", "Inventory Adjustments", GlAccount.AccountType.EQUITY);
        GlAccount revenue = account("4000", "Sales Revenue", GlAccount.AccountType.REVENUE);
        GlAccount cogs = account("5000", "Cost of Goods Sold", GlAccount.AccountType.EXPENSE);
        GlAccount inputTax = account("1300", "Input Tax Recoverable", GlAccount.AccountType.ASSET);
        GlAccount shrinkage = account("5100", "Shrinkage & Spoilage", GlAccount.AccountType.EXPENSE);
        GlAccount freight = account("5200", "Freight & Duty", GlAccount.AccountType.EXPENSE);
        GlAccount revaluation = account("5300", "Inventory Revaluation", GlAccount.AccountType.EXPENSE);
        GlAccount discount = account("5900", "Sales Discounts", GlAccount.AccountType.EXPENSE);

        mapping(GlMapping.EventType.CASH, cash, "Cash receipts");
        mapping(GlMapping.EventType.ACCOUNTS_RECEIVABLE, receivable, "Credit sales receivable");
        mapping(GlMapping.EventType.INVENTORY, inventory, "Inventory asset");
        mapping(GlMapping.EventType.ACCOUNTS_PAYABLE, payable, "Supplier payables");
        mapping(GlMapping.EventType.SALES_TAX_PAYABLE, taxPayable, "Tax collected");
        mapping(GlMapping.EventType.SALES_REVENUE, revenue, "Sales revenue");
        mapping(GlMapping.EventType.COST_OF_GOODS_SOLD, cogs, "Cost of goods sold");
        mapping(GlMapping.EventType.OPERATING_EXPENSE, shrinkage, "Shrinkage / damages");
        mapping(GlMapping.EventType.FREIGHT_IN, freight, "Landed freight / duty");
        mapping(GlMapping.EventType.SALES_DISCOUNT, discount, "Promotions and discounts");
        mapping(GlMapping.EventType.INVENTORY_REVALUATION, revaluation, "Inventory revaluation reserve");
        mapping(GlMapping.EventType.INVENTORY_ADJUSTMENT, inventoryAdjustment, "Neutral inventory corrections / opening balances");
        mapping(GlMapping.EventType.EXPENSE_PAYABLE, expensePayable, "Expenses awaiting payment");
        mapping(GlMapping.EventType.INPUT_TAX, inputTax, "Recoverable purchase input tax");
        mapping(GlMapping.EventType.REFUND_LIABILITY, refundLiability, "Store credit owed to customers");
        mapping(GlMapping.EventType.TENDER_CLEARING, tenderClearing, "Non-cash tender clearing");
        mapping(GlMapping.EventType.CASH_CLEARING, cashClearing, "Manual drawer / safe-drop clearing");
    }

    private GlAccount account(String code, String name, GlAccount.AccountType type) {
        return accountRepository.findByCode(code).orElseGet(() -> {
            GlAccount account = new GlAccount();
            account.setCode(code);
            account.setName(name);
            account.setType(type);
            return accountRepository.save(account);
        });
    }

    private void mapping(GlMapping.EventType eventType, GlAccount account, String description) {
        if (mappingRepository.findByEventType(eventType).isPresent()) {
            return;
        }
        GlMapping mapping = new GlMapping();
        mapping.setEventType(eventType);
        mapping.setAccount(account);
        mapping.setDescription(description);
        mappingRepository.save(mapping);
    }

    // ------------------------------------------------------------------
    // Journal export
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JournalEntryResponse> journal(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : shopTimeService.today().withDayOfMonth(1);
        LocalDate end = to != null ? to : shopTimeService.today();
        if (end.isBefore(start)) {
            throw new BusinessException("Journal end date cannot be before its start date");
        }
        LocalDateTime startTs = start.atStartOfDay();
        LocalDateTime endTs = end.plusDays(1).atStartOfDay();

        Map<GlMapping.EventType, GlAccount> accounts = mappingRepository.findAll().stream()
                .collect(Collectors.toMap(GlMapping::getEventType, GlMapping::getAccount,
                        (a, b) -> a));
        List<JournalEntryResponse> entries = new ArrayList<>();

        if (ledgerReadService.isActive()) {
            postLedger(entries, accounts, startTs, endTs);
            postLedgerPurchaseTax(entries, accounts, startTs, endTs);
        } else if (legacyEnabled()) {
            postPurchases(entries, accounts, start, end);
            postSales(entries, accounts, startTs, endTs);
            postSaleReturns(entries, accounts, startTs, endTs);
            postExpenses(entries, accounts, start, end);
            postStockMovements(entries, accounts, startTs, endTs);
        } else {
            throw new BusinessException("Ledger cutover not completed: the legacy GL path is disabled. "
                    + "Backfill history and activate ledger reads, or enable accounting.ledger.legacy.enabled.");
        }
        postArPayments(entries, accounts, startTs, endTs);
        postPurchasePayments(entries, accounts, startTs, endTs);
        postDrawerMovements(entries, accounts, startTs, endTs);

        entries.sort(Comparator.comparing(JournalEntryResponse::getDate)
                .thenComparing(JournalEntryResponse::getReference)
                .thenComparing(JournalEntryResponse::getAccountCode));
        return entries;
    }

    private void postLedger(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                            LocalDateTime start, LocalDateTime end) {
        List<ProfitLedger> profitRows = profitLedgerRepository
                .findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(start, end);
        java.util.Set<Long> profitPostedMovements = new java.util.HashSet<>();
        for (ProfitLedger row : profitRows) {
            LocalDate date = row.getOccurredAt().toLocalDate();
            String reference = row.getReference() != null ? row.getReference() : row.getSourceType() + "-" + row.getSourceId();
            BigDecimal revenue = row.getNetRevenue();
            BigDecimal tax = row.getTaxAmount();
            if (row.getEventType() == ProfitLedger.EventType.SALE
                    || row.getEventType() == ProfitLedger.EventType.RETURN
                    || row.getEventType() == ProfitLedger.EventType.VOID) {
                if (row.getSettlementCash() != null || row.getSettlementClearing() != null
                        || row.getSettlementAr() != null || row.getSettlementRefundLiability() != null) {
                    addSettlementSplit(entries, accounts, date, reference, row);
                } else {
                    if (row.getSettlementAccount() == null) {
                        throw new BusinessException("Missing ledger settlement snapshot for " + reference);
                    }
                    BigDecimal settlement = revenue.add(tax);
                    add(entries, accounts, date, reference, "Ledger settlement", row.getSettlementAccount(),
                            settlement.max(BigDecimal.ZERO), settlement.negate().max(BigDecimal.ZERO));
                }
                add(entries, accounts, date, reference, "Ledger net sales revenue", GlMapping.EventType.SALES_REVENUE,
                        revenue.negate().max(BigDecimal.ZERO), revenue.max(BigDecimal.ZERO));
                add(entries, accounts, date, reference, "Ledger sales tax", GlMapping.EventType.SALES_TAX_PAYABLE,
                        tax.negate().max(BigDecimal.ZERO), tax.max(BigDecimal.ZERO));
                addSignedPair(entries, accounts, date, reference, "Ledger COGS", GlMapping.EventType.COST_OF_GOODS_SOLD,
                        GlMapping.EventType.INVENTORY, row.getCogs());
            } else if (row.getEventType() == ProfitLedger.EventType.WRITE_OFF) {
                addSignedPair(entries, accounts, date, reference, "Ledger inventory loss", GlMapping.EventType.OPERATING_EXPENSE,
                        GlMapping.EventType.INVENTORY, row.getExpense());
                if (row.getSourceType().equals("STOCK_MOVEMENT")) profitPostedMovements.add(row.getSourceId());
            } else if (row.getEventType() == ProfitLedger.EventType.INVENTORY_GAIN) {
                addPair(entries, accounts, date, reference, "Ledger inventory gain",
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.INVENTORY_ADJUSTMENT,
                        row.getExpense().abs());
                if (row.getSourceType().equals("STOCK_MOVEMENT")) profitPostedMovements.add(row.getSourceId());
            } else if (row.getEventType() == ProfitLedger.EventType.EXPENSE
                    || row.getEventType() == ProfitLedger.EventType.PURCHASE_VARIANCE) {
                addSignedPair(entries, accounts, date, reference, "Ledger operating expense", GlMapping.EventType.OPERATING_EXPENSE,
                        GlMapping.EventType.EXPENSE_PAYABLE, row.getExpense());
            }
        }
        for (InventoryLedger row : inventoryLedgerRepository
                .findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(start, end)) {
            InventoryLedger.EventType type = row.getEventType();
            if (type == InventoryLedger.EventType.SALE || type == InventoryLedger.EventType.RETURN
                    || type == InventoryLedger.EventType.VOID || type == InventoryLedger.EventType.TRANSFER) continue;
            if (row.getSourceType().equals("STOCK_MOVEMENT")
                    && profitPostedMovements.contains(row.getSourceId())) continue;
            GlMapping.EventType offset = GlMapping.EventType.INVENTORY_ADJUSTMENT;
            if (type == InventoryLedger.EventType.RECEIPT || type == InventoryLedger.EventType.RTV
                    || row.getSourceType().equals("PURCHASE_REVERSAL")) offset = GlMapping.EventType.ACCOUNTS_PAYABLE;
            else if (type == InventoryLedger.EventType.REVALUATION) offset = GlMapping.EventType.INVENTORY_REVALUATION;
            addSignedPair(entries, accounts, row.getOccurredAt().toLocalDate(), row.getSourceType() + "-" + row.getSourceId(),
                    row.getReason() != null ? row.getReason() : type.name(),
                    GlMapping.EventType.INVENTORY, offset, row.getValueDelta());
        }
    }

    /**
     * Defect 3: the inventory ledger only carries the goods value of a receipt,
     * while the supplier payable (and therefore the supplier payment) includes
     * recoverable tax. Post that tax as an asset at the same event date, and
     * reverse it on the matching RTV row, so AP settles to zero.
     */
    private void postLedgerPurchaseTax(List<JournalEntryResponse> entries,
                                       Map<GlMapping.EventType, GlAccount> accounts,
                                       LocalDateTime startTs, LocalDateTime endTs) {
        Map<Long, Purchase> purchases = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> taxShares = new HashMap<>();
        for (InventoryLedger row : inventoryLedgerRepository
                .findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(startTs, endTs)) {
            boolean receipt = row.getEventType() == InventoryLedger.EventType.RECEIPT
                    && "PURCHASE".equals(row.getSourceType());
            boolean rtv = row.getEventType() == InventoryLedger.EventType.RTV
                    && "PURCHASE_RTV".equals(row.getSourceType());
            if ((!receipt && !rtv) || row.getSourceId() == null || row.getQtyDelta() == null
                    || row.getLineKey() == null) {
                continue;
            }
            Purchase purchase = purchases.computeIfAbsent(row.getSourceId(),
                    id -> purchaseRepository.findById(id).orElse(null));
            if (purchase == null || !purchase.isTaxRecoverable()) {
                continue;
            }
            BigDecimal tax = money(purchase.getTaxAmount());
            if (tax.signum() == 0) {
                continue;
            }
            Long itemId = parseLedgerItemId(row.getLineKey());
            if (itemId == null) {
                continue;
            }
            Map<Long, BigDecimal> shares = taxShares.computeIfAbsent(purchase.getId(), id -> {
                List<LandedCostAllocator.Line> lines = new ArrayList<>();
                for (PurchaseItem item : purchase.getItems()) {
                    int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                    lines.add(new LandedCostAllocator.Line(item.getId(), item.getUnitCost(), qty,
                            item.getProduct().getWeight()));
                }
                return LandedCostAllocator.allocateByValue(lines, tax);
            });
            PurchaseItem item = purchase.getItems().stream()
                    .filter(candidate -> itemId.equals(candidate.getId()))
                    .findFirst().orElse(null);
            int ordered = item != null && item.getQuantity() != null ? item.getQuantity() : 0;
            if (ordered <= 0) {
                continue;
            }
            BigDecimal share = shares.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal fx = purchase.getExchangeRate() != null ? purchase.getExchangeRate() : BigDecimal.ONE;
            BigDecimal amount = share.divide(BigDecimal.valueOf(ordered), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(Math.abs(row.getQtyDelta())))
                    .multiply(fx).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() == 0) {
                continue;
            }
            LocalDate date = row.getOccurredAt().toLocalDate();
            String reference = purchase.getPurchaseNumber() + "-tax-" + itemId;
            if (receipt) {
                addPair(entries, accounts, date, reference,
                        "Recoverable input tax on purchase " + purchase.getPurchaseNumber(),
                        GlMapping.EventType.INPUT_TAX, GlMapping.EventType.ACCOUNTS_PAYABLE, amount);
            } else {
                addPair(entries, accounts, date, reference,
                        "Recoverable input tax reversed on return to vendor " + purchase.getPurchaseNumber(),
                        GlMapping.EventType.ACCOUNTS_PAYABLE, GlMapping.EventType.INPUT_TAX, amount);
            }
        }
    }

    private static Long parseLedgerItemId(String lineKey) {
        int colon = lineKey.indexOf(':');
        try {
            return Long.valueOf(colon > 0 ? lineKey.substring(0, colon) : lineKey);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void addSettlementSplit(List<JournalEntryResponse> entries,
                                    Map<GlMapping.EventType, GlAccount> accounts,
                                    LocalDate date, String reference, ProfitLedger row) {
        addSignedSettlement(entries, accounts, date, reference, GlMapping.EventType.CASH, row.getSettlementCash());
        addSignedSettlement(entries, accounts, date, reference, GlMapping.EventType.TENDER_CLEARING,
                row.getSettlementClearing());
        addSignedSettlement(entries, accounts, date, reference, GlMapping.EventType.ACCOUNTS_RECEIVABLE,
                row.getSettlementAr());
        BigDecimal liability = LedgerPostingService.refundLiabilityOf(row);
        if (liability != null) {
            addSignedSettlement(entries, accounts, date, reference, GlMapping.EventType.REFUND_LIABILITY, liability);
        }
    }

    private void addSignedSettlement(List<JournalEntryResponse> entries,
                                     Map<GlMapping.EventType, GlAccount> accounts,
                                     LocalDate date, String reference, GlMapping.EventType account,
                                     BigDecimal amount) {
        BigDecimal value = money(amount);
        add(entries, accounts, date, reference, "Ledger settlement", account,
                value.max(BigDecimal.ZERO), value.negate().max(BigDecimal.ZERO));
    }

    private void addSignedPair(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                               LocalDate date, String reference, String description,
                               GlMapping.EventType debit, GlMapping.EventType credit, BigDecimal amount) {
        if (amount.signum() >= 0) {
            addPair(entries, accounts, date, reference, description, debit, credit, amount);
        } else {
            addPair(entries, accounts, date, reference, description, credit, debit, amount.abs());
        }
    }

    private void postPurchases(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                               LocalDate start, LocalDate end) {
        for (Purchase purchase : purchaseRepository.findForJournal(start, end)) {
            if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.ORDERED) {
                continue;
            }
            BigDecimal fx = purchase.getExchangeRate() != null ? purchase.getExchangeRate() : BigDecimal.ONE;
            LocalDate date = purchase.getReceivedAt() != null
                    ? purchase.getReceivedAt().toLocalDate() : purchase.getPurchaseDate();
            boolean recoverable = purchase.isTaxRecoverable();
            // Build value-weighted lines so recoverable tax can be allocated
            // across purchase items pro-rata to line value (same basis used by
            // applyLandedCosts for non-recoverable tax).
            List<LandedCostAllocator.Line> lines = new ArrayList<>();
            for (PurchaseItem item : purchase.getItems()) {
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                lines.add(new LandedCostAllocator.Line(item.getId(), item.getUnitCost(), qty,
                        item.getProduct().getWeight()));
            }
            Map<Long, BigDecimal> taxShares = recoverable
                    ? LandedCostAllocator.allocateByValue(lines, purchase.getTaxAmount())
                    : Collections.emptyMap();
            // Journal per purchase item: an item that already has a receiving
            // movement was journaled on a prior run; only newly received lines
            // are posted. This prevents understating payables/inventory when a
            // PO is partially received, journaled, then receives more later.
            for (PurchaseItem item : purchase.getItems()) {
                int received = item.getReceivedQuantity() != null ? item.getReceivedQuantity() : 0;
                if (received <= 0) {
                    continue;
                }
                if (recoverable) {
                    int ordered = item.getQuantity() != null ? item.getQuantity() : 0;
                    BigDecimal taxShare = taxShares.getOrDefault(item.getId(), BigDecimal.ZERO);
                    if (ordered > 0 && taxShare.signum() > 0) {
                        BigDecimal receivedTax = taxShare.multiply(BigDecimal.valueOf(received))
                                .divide(BigDecimal.valueOf(ordered), 2, RoundingMode.HALF_UP)
                                .multiply(fx).setScale(2, RoundingMode.HALF_UP);
                        if (receivedTax.signum() > 0) {
                            addPair(entries, accounts, date,
                                    purchase.getPurchaseNumber() + "-tax-" + item.getId(),
                                    "Recoverable input tax on purchase " + purchase.getPurchaseNumber(),
                                    GlMapping.EventType.INPUT_TAX, GlMapping.EventType.ACCOUNTS_PAYABLE,
                                    receivedTax);
                        }
                    }
                }
                BigDecimal itemAmount = item.getUnitCost() != null
                        ? item.getUnitCost().multiply(BigDecimal.valueOf(received)).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                itemAmount = itemAmount.multiply(fx).setScale(2, RoundingMode.HALF_UP);
                if (itemAmount.signum() == 0) {
                    continue;
                }
                String reference = purchase.getPurchaseNumber() + "-item-" + item.getId();
                String description = "Purchase received from "
                        + (purchase.getSupplier() != null ? purchase.getSupplier().getName() : "supplier");
                // Goods value to inventory, credited against the supplier payable.
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.ACCOUNTS_PAYABLE, itemAmount);
            }
        }
    }

    private void postSales(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                           LocalDateTime startTs, LocalDateTime endTs) {
        // Originals include voided sales so the reversal below nets against a
        // posted original rather than creating an unsupported credit.
        for (Sale sale : saleRepository.findForJournal(startTs, endTs)) {
            postSaleOriginal(entries, accounts, sale);
        }
        // Dated void reversals (P6). Reverse the original net of cumulative
        // returns so a partly-returned then voided sale nets to zero, matching
        // the P&L which excludes voided sales. Cross-period immutability is Phase 4.
        for (Sale sale : saleRepository.findVoidedForJournal(startTs, endTs)) {
            postVoidReversal(entries, accounts, sale);
        }
    }

    private void postSaleOriginal(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                  Sale sale) {
        BigDecimal total = money(sale.getTotalAmount());
        // Promotion / price-list discounts are already netted out of the stored
        // line prices (subtotal); only the header discount is an extra debit.
        BigDecimal discount = money(sale.getDiscountAmount());
        BigDecimal revenue = money(sale.getSubtotal());
        BigDecimal tax = money(sale.getTaxAmount());
        String reference = sale.getInvoiceNumber();
        LocalDate date = sale.getSaleDate().toLocalDate();

        postTenderSplit(entries, accounts, date, reference, sale, total, "Sale settlement");
        if (discount.signum() > 0) {
            add(entries, accounts, date, reference, "Sale discounts",
                    GlMapping.EventType.SALES_DISCOUNT, discount, BigDecimal.ZERO);
        }
        add(entries, accounts, date, reference, "Sales revenue",
                GlMapping.EventType.SALES_REVENUE, BigDecimal.ZERO, revenue);
        if (tax.signum() > 0) {
            add(entries, accounts, date, reference, "Sales tax",
                    GlMapping.EventType.SALES_TAX_PAYABLE, BigDecimal.ZERO, tax);
        }
        BigDecimal cogs = grossCogs(sale);
        if (cogs.signum() > 0) {
            addPair(entries, accounts, date, reference, "Cost of goods sold",
                    GlMapping.EventType.COST_OF_GOODS_SOLD, GlMapping.EventType.INVENTORY, cogs);
        }
    }

    private void postVoidReversal(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                  Sale sale) {
        if (sale.getVoidedAt() == null) {
            return;
        }
        LocalDate date = sale.getVoidedAt().toLocalDate();
        String reference = sale.getInvoiceNumber();
        BigDecimal returnedGross = BigDecimal.ZERO;
        for (SaleReturn ret : saleReturnRepository.findBySaleIdOrderByReturnDateDesc(sale.getId())) {
            returnedGross = returnedGross.add(money(ret.getTotalReturnAmount()));
        }
        BigDecimal total = money(sale.getTotalAmount());
        BigDecimal tax = money(sale.getTaxAmount());
        BigDecimal revenue = money(sale.getSubtotal());
        BigDecimal discount = money(sale.getDiscountAmount());
        BigDecimal returnedTax = proportionalTax(returnedGross, tax, total);
        BigDecimal returnedNet = returnedGross.subtract(returnedTax);
        BigDecimal voidTender = total.subtract(returnedGross);
        BigDecimal voidTax = tax.subtract(returnedTax);
        BigDecimal voidRevenue = revenue.subtract(returnedNet);

        // N4 void-tender netting: credit AR for the still-outstanding portion,
        // CASH for net cash refunded. Non-cash goes to TENDER_CLEARING for cash
        // sales (card reversal) or REFUND_LIABILITY for credit sales (store credit).
        BigDecimal cashPaid = moneyVoidCash(sale);
        BigDecimal arCashPaid = moneyVoidArCash(sale);
        BigDecimal arNonCashPaid = moneyVoidArNonCash(sale);
        BigDecimal saleNonCashPaid = moneyVoidSaleNonCash(sale);
        BigDecimal cashAlreadyRefunded = safeSumVoid(sale);

        BigDecimal cashCollected = cashPaid.add(arCashPaid);
        BigDecimal nonCashCollected = arNonCashPaid.add(saleNonCashPaid);
        BigDecimal refundsAgainstCash = cashAlreadyRefunded.min(cashCollected);
        BigDecimal refundsAgainstNonCash = cashAlreadyRefunded.subtract(refundsAgainstCash)
                .max(BigDecimal.ZERO);
        BigDecimal remainingCashOwed = cashCollected.subtract(refundsAgainstCash).max(BigDecimal.ZERO);
        BigDecimal remainingNonCashOwed = nonCashCollected.subtract(refundsAgainstNonCash)
                .max(BigDecimal.ZERO);

        BigDecimal netCashRefund = remainingCashOwed;
        BigDecimal netNonCashRefund = remainingNonCashOwed;

        boolean isCreditSale = sale.getSaleType() == Sale.SaleType.CREDIT;

        if (netCashRefund.signum() > 0) {
            add(entries, accounts, date, reference, "Void reversal",
                    GlMapping.EventType.CASH, BigDecimal.ZERO, netCashRefund);
        }
        if (netNonCashRefund.signum() > 0) {
            GlMapping.EventType nonCashAccount = isCreditSale
                    ? GlMapping.EventType.REFUND_LIABILITY
                    : GlMapping.EventType.TENDER_CLEARING;
            add(entries, accounts, date, reference, "Void reversal",
                    nonCashAccount, BigDecimal.ZERO, netNonCashRefund);
        }
        BigDecimal outstanding = voidTender.subtract(netCashRefund).subtract(netNonCashRefund);
        if (outstanding.signum() > 0) {
            // Outstanding portion reverses AR for credit sales, CASH for cash sales.
            GlMapping.EventType outstandingAccount = isCreditSale
                    ? GlMapping.EventType.ACCOUNTS_RECEIVABLE
                    : GlMapping.EventType.CASH;
            add(entries, accounts, date, reference, "Void reversal",
                    outstandingAccount, BigDecimal.ZERO, outstanding);
        }

        if (discount.signum() > 0) {
            add(entries, accounts, date, reference, "Void reversal discounts",
                    GlMapping.EventType.SALES_DISCOUNT, BigDecimal.ZERO, discount);
        }

        add(entries, accounts, date, reference, "Void reversal revenue",
                GlMapping.EventType.SALES_REVENUE, voidRevenue, BigDecimal.ZERO);
        if (voidTax.signum() > 0) {
            add(entries, accounts, date, reference, "Void reversal tax",
                    GlMapping.EventType.SALES_TAX_PAYABLE, voidTax, BigDecimal.ZERO);
        }
        BigDecimal cogs = netCogs(sale);
        if (cogs.signum() > 0) {
            addPair(entries, accounts, date, reference, "Void reversal COGS",
                    GlMapping.EventType.INVENTORY, GlMapping.EventType.COST_OF_GOODS_SOLD, cogs);
        }
    }

    private BigDecimal moneyVoidCash(Sale sale) {
        List<SalePayment> payments = sale.getPayments() != null ? sale.getPayments() : List.of();
        return payments.stream()
                .filter(p -> p.getPaymentMethod() == PaymentMethod.CASH)
                .map(p -> money(p.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal moneyVoidArCash(Sale sale) {
        List<ArPayment> payments = arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(sale.getId());
        return payments.stream()
                .filter(p -> p.getReversedAt() == null
                        && p.getPaymentMethod() == PaymentMethod.CASH)
                .map(p -> money(p.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal moneyVoidArNonCash(Sale sale) {
        List<ArPayment> payments = arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(sale.getId());
        return payments.stream()
                .filter(p -> p.getReversedAt() == null
                        && p.getPaymentMethod() != PaymentMethod.CASH)
                .map(p -> money(p.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal moneyVoidSaleNonCash(Sale sale) {
        List<SalePayment> payments = sale.getPayments() != null ? sale.getPayments() : List.of();
        return payments.stream()
                .filter(p -> p.getPaymentMethod() != PaymentMethod.CASH)
                .map(p -> money(p.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal safeSumVoid(Sale sale) {
        return safeSum(saleReturnRepository.sumRefundedAmountBySaleId(sale.getId()));
    }

    private void postTenderSplit(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                 LocalDate date, String reference, Sale sale, BigDecimal signedAmount,
                                 String description) {
        List<SalePayment> payments = sale.getPayments() != null ? sale.getPayments() : List.of();
        BigDecimal paidTotal = payments.stream().map(SalePayment::getAmount).filter(java.util.Objects::nonNull)
                .map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (payments.isEmpty() || paidTotal.signum() == 0) {
            GlMapping.EventType fallback = sale.getSaleType() == Sale.SaleType.CREDIT
                    ? GlMapping.EventType.ACCOUNTS_RECEIVABLE
                    : GlMapping.EventType.CASH;
            add(entries, accounts, date, reference, description, fallback,
                    signedAmount.max(BigDecimal.ZERO), signedAmount.negate().max(BigDecimal.ZERO));
            return;
        }
        BigDecimal remaining = signedAmount;
        for (int i = 0; i < payments.size(); i++) {
            SalePayment payment = payments.get(i);
            BigDecimal part = i == payments.size() - 1 ? remaining
                    : signedAmount.multiply(money(payment.getAmount()).abs())
                    .divide(paidTotal, 2, RoundingMode.HALF_UP);
            remaining = remaining.subtract(part);
            GlMapping.EventType account = payment.getPaymentMethod() == PaymentMethod.CASH
                    ? GlMapping.EventType.CASH
                    : payment.getPaymentMethod() == PaymentMethod.CREDIT
                    ? GlMapping.EventType.ACCOUNTS_RECEIVABLE : GlMapping.EventType.TENDER_CLEARING;
            add(entries, accounts, date, reference, description, account,
                    part.max(BigDecimal.ZERO), part.negate().max(BigDecimal.ZERO));
        }
    }

    // Returns reverse revenue and tax at the return date and credit the refund
    // tender. COGS is also reversed here, for the units this return actually
    // sent back: the sale date booked the full sold quantity, so without this
    // reversal a month's COGS would only be correct by mutating the original
    // period through SaleItem.quantityRefunded. Dated reversal keeps every
    // month frozen once it is reported.
    private void postSaleReturns(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                 LocalDateTime startTs, LocalDateTime endTs) {
        for (SaleReturn ret : saleReturnRepository.findForJournal(startTs, endTs)) {
            BigDecimal gross = money(ret.getTotalReturnAmount());
            if (gross.signum() == 0) {
                continue;
            }
            Sale sale = ret.getSale();
            BigDecimal taxShare = proportionalTax(gross, money(sale.getTaxAmount()), money(sale.getTotalAmount()));
            BigDecimal net = gross.subtract(taxShare);
            BigDecimal debtReduction = BigDecimal.ZERO;
            BigDecimal refundDue = gross;
            BigDecimal debtReductionAmount = money(ret.getDebtReductionAmount());
            BigDecimal refundAmount = money(ret.getRefundAmount());
            if (debtReductionAmount.signum() > 0 || refundAmount.signum() > 0) {
                debtReduction = debtReductionAmount;
                refundDue = refundAmount;
            }
            LocalDate date = ret.getReturnDate().toLocalDate();
            String reference = "RET-" + ret.getId();

            add(entries, accounts, date, reference, "Sales return",
                    GlMapping.EventType.SALES_REVENUE, net, BigDecimal.ZERO);
            if (taxShare.signum() > 0) {
                add(entries, accounts, date, reference, "Sales return tax",
                        GlMapping.EventType.SALES_TAX_PAYABLE, taxShare, BigDecimal.ZERO);
            }
            BigDecimal returnedCogs = returnCogs(ret);
            if (returnedCogs.signum() > 0) {
                addPair(entries, accounts, date, reference, "Sales return COGS",
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.COST_OF_GOODS_SOLD, returnedCogs);
            }
            if (debtReduction.signum() > 0) {
                add(entries, accounts, date, reference, "Sales return debt reduction",
                        GlMapping.EventType.ACCOUNTS_RECEIVABLE, BigDecimal.ZERO, debtReduction);
            }
            if (refundDue.signum() > 0) {
                GlMapping.EventType credit = refundTender(ret.getRefundMethod());
                add(entries, accounts, date, reference, "Sales return refund", credit,
                        BigDecimal.ZERO, refundDue);
            }
        }
    }

    /** COGS of the units this return sent back, valued at the original sale cost. */
    private BigDecimal returnCogs(SaleReturn ret) {
        return ret.getItems().stream()
                .map(item -> CostCalculationUtils.calculateCogs(
                        item.getSaleItem().getCostPriceAtSale(),
                        item.getQuantityReturned() != null ? item.getQuantityReturned() : 0))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void postArPayments(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                LocalDateTime startTs, LocalDateTime endTs) {
        for (ArPayment payment : arPaymentRepository.findForJournal(startTs, endTs)) {
            BigDecimal amount = money(payment.getAmount());
            if (amount.signum() == 0) {
                continue;
            }
            GlMapping.EventType debit = payment.getPaymentMethod() == PaymentMethod.CASH
                    ? GlMapping.EventType.CASH : GlMapping.EventType.TENDER_CLEARING;
            addPair(entries, accounts, payment.getPaymentDate().toLocalDate(), "ARP-" + payment.getId(),
                    "AR collection", debit, GlMapping.EventType.ACCOUNTS_RECEIVABLE, amount);
        }
        // Dated reversals: reversed payments are journaled on the reversal date so
        // closed periods do not change retrospectively. Non-cash reversals convert
        // to store credit (REFUND_LIABILITY), cash reversals return to the drawer.
        // When the invoice itself was voided, the collection reversal is handled by
        // postVoidReversal (which nets against returns), so skip the standalone
        // ARP-REV row here to avoid double-counting.
        for (ArPayment payment : arPaymentRepository.findReversedForJournal(startTs, endTs)) {
            // Legacy voids are reversed by postVoidReversal, which nets returns.
            // The ledger path has no equivalent standalone reversal, so the
            // dated ARP-REV rows must be journaled there or AR and Cash drift.
            if (!ledgerReadService.isActive()
                    && payment.getInvoice() != null && payment.getInvoice().getIsVoided()) {
                continue;
            }
            BigDecimal amount = money(payment.getAmount());
            if (amount.signum() == 0) {
                continue;
            }
            LocalDate date = payment.getReversedAt() != null
                    ? payment.getReversedAt().toLocalDate() : payment.getPaymentDate().toLocalDate();
            GlMapping.EventType credit = payment.getPaymentMethod() == PaymentMethod.CASH
                    ? GlMapping.EventType.CASH : GlMapping.EventType.REFUND_LIABILITY;
            addPair(entries, accounts, date, "ARP-" + payment.getId() + "-REV",
                    "AR payment reversed", GlMapping.EventType.ACCOUNTS_RECEIVABLE, credit, amount);
        }
    }

    // O3: supplier payments settle the payable at the payment date. The
    // invoice-currency amount is converted at the purchase exchange rate
    // because the journal is base-currency. The linked drawer PAY_OUT is
    // skipped by postDrawerMovements so cash is never counted twice.
    private void postPurchasePayments(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                      LocalDateTime startTs, LocalDateTime endTs) {
        for (PurchasePayment payment : purchasePaymentRepository.findForJournal(startTs, endTs)) {
            BigDecimal amount = money(payment.getAmount());
            if (amount.signum() == 0) {
                continue;
            }
            BigDecimal baseAmount = amount.multiply(payment.getPurchase().getExchangeRate())
                    .setScale(2, RoundingMode.HALF_UP);
            GlMapping.EventType credit = payment.getPaymentMethod() == PaymentMethod.CASH
                    ? GlMapping.EventType.CASH : GlMapping.EventType.TENDER_CLEARING;
            addPair(entries, accounts, payment.getPaymentDate().toLocalDate(), "PP-" + payment.getId(),
                    "Purchase payment", GlMapping.EventType.ACCOUNTS_PAYABLE, credit, baseAmount);
        }
    }

    // Every Expense record (including the PURCHASE_VARIANCE expense raised on
    // close) is posted once here, so GL expense equals P&L expense (I4). Purchase
    // variance is never posted again from Purchase.varianceAmount, and capitalized
    // freight lives in inventory valuation, not here. O4: expenses flagged as
    // purchase-capitalized are excluded — their cost already sits in inventory.
    private void postExpenses(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                              LocalDate start, LocalDate end) {
        for (Expense expense : expenseRepository.findFiltered(null, start, end)) {
            if (expense.isCapitalized()) {
                continue;
            }
            BigDecimal amount = money(expense.getAmount());
            if (amount.signum() == 0) {
                continue;
            }
            addPair(entries, accounts, expense.getExpenseDate(), "EXP-" + expense.getId(),
                    expense.getDescription() != null ? expense.getDescription() : "Expense",
                    GlMapping.EventType.OPERATING_EXPENSE, GlMapping.EventType.EXPENSE_PAYABLE, amount);
        }
    }

    // Manual drawer events (opening float, pay-in/out, safe drop, adjustment) are
    // posted against cash clearing. Movements owned by a sale, return, AR
    // collection or purchase payment are skipped — those events post their own
    // tender leg, so posting the drawer duplicate would double count cash.
    private void postDrawerMovements(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                     LocalDateTime startTs, LocalDateTime endTs) {
        for (CashDrawerMovement movement : cashDrawerMovementRepository.findForJournal(startTs, endTs)) {
            String refType = movement.getReferenceType() != null
                    ? movement.getReferenceType().trim().toUpperCase() : "";
            if (refType.equals("SALE") || refType.equals("SALE_RETURN") || refType.equals("AR_PAYMENT")
                    || refType.equals("PURCHASE_PAYMENT")) {
                continue;
            }
            if (movement.getMovementType() == DrawerMovementType.CASH_SALE
                    || movement.getMovementType() == DrawerMovementType.NO_SALE_OPEN) {
                continue;
            }
            BigDecimal amount = money(movement.getAmount());
            if (amount.signum() == 0) {
                continue;
            }
            LocalDate date = movement.getOccurredAt().toLocalDate();
            String reference = "DRW-" + movement.getId();
            String description = movement.getReason() != null
                    ? movement.getReason() : movement.getMovementType().name();
            if (amount.signum() > 0) {
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.CASH, GlMapping.EventType.CASH_CLEARING, amount);
            } else {
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.CASH_CLEARING, GlMapping.EventType.CASH, amount.abs());
            }
        }
    }

    private void postStockMovements(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                                    LocalDateTime startTs, LocalDateTime endTs) {
        for (StockMovement movement : stockMovementRepository.findForJournal(startTs, endTs)) {
            BigDecimal valuation = money(movement.getValuationAmount());
            if (valuation.signum() == 0) {
                continue;
            }
            LocalDate date = movement.getMovementDate().toLocalDate();
            String reference = "MOV-" + movement.getId();
            String description = movement.getDescription() != null
                    ? movement.getDescription() : "Stock movement";
            StockMovement.ReferenceType refType = movement.getReferenceType();

            // Operating losses come from the shared InventoryLossService so GL
            // expense equals P&L expense (P3 / I4).
            if (inventoryLossService.isOperatingLoss(movement)) {
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.OPERATING_EXPENSE, GlMapping.EventType.INVENTORY, valuation.abs());
                continue;
            }
            if (refType == StockMovement.ReferenceType.RECEIVING) {
                // Capitalized receipt at its receipt date (P4.5). Recoverable
                // input-tax recognition needs per-event snapshots (Phase 4).
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.ACCOUNTS_PAYABLE, valuation.abs());
                continue;
            }
            if (refType == StockMovement.ReferenceType.PURCHASE || refType == StockMovement.ReferenceType.RTV) {
                // Reversal / return-to-vendor reduces the supplier payable, not
                // operating expense (P4.3 / P4.4).
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.ACCOUNTS_PAYABLE, GlMapping.EventType.INVENTORY, valuation.abs());
                continue;
            }
            if (movement.getMovementType() == StockMovement.MovementType.REVALUATION) {
                // Provisional revaluation reserve, never operating expense (P4.5).
                if (valuation.signum() < 0) {
                    addPair(entries, accounts, date, reference, description,
                            GlMapping.EventType.INVENTORY_REVALUATION, GlMapping.EventType.INVENTORY, valuation.abs());
                } else {
                    addPair(entries, accounts, date, reference, description,
                            GlMapping.EventType.INVENTORY, GlMapping.EventType.INVENTORY_REVALUATION, valuation);
                }
                continue;
            }
            if (refType == StockMovement.ReferenceType.OPENING_BALANCE) {
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.INVENTORY_ADJUSTMENT, valuation.abs());
                continue;
            }
            // Neutral corrections and count/adjustment gains: balance-sheet only,
            // no P&L impact, so they never inflate operating loss.
            if (valuation.signum() < 0) {
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.INVENTORY_ADJUSTMENT, GlMapping.EventType.INVENTORY, valuation.abs());
            } else {
                addPair(entries, accounts, date, reference, description,
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.INVENTORY_ADJUSTMENT, valuation);
            }
        }
    }

    /**
     * COGS booked on the sale date at the full sold quantity. The original
     * journal entry must never depend on the mutable quantityRefunded counter,
     * otherwise reporting (or re-reporting) an earlier month changes its COGS
     * every time a later refund happens.
     */
    private BigDecimal grossCogs(Sale sale) {
        return sale.getItems().stream()
                .map(item -> CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(),
                        item.getQuantity() != null ? item.getQuantity() : 0))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * COGS still carried by the sale after its returns: the void reversal books
     * this remainder, because postSaleReturns already reversed the returned
     * units on their own dates.
     */
    private BigDecimal netCogs(Sale sale) {
        return sale.getItems().stream()
                .map(item -> CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(),
                        Math.max(0, (item.getQuantity() != null ? item.getQuantity() : 0)
                                - (item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Tax share of a gross amount using the invoice's effective tax fraction. */
    private BigDecimal proportionalTax(BigDecimal gross, BigDecimal tax, BigDecimal total) {
        if (total == null || total.signum() == 0 || gross.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(tax).divide(total, 2, RoundingMode.HALF_UP);
    }

    private GlMapping.EventType refundTender(PaymentMethod method) {
        if (method == null) {
            return GlMapping.EventType.CASH;
        }
        return switch (method) {
            case CASH -> GlMapping.EventType.CASH;
            case CREDIT -> GlMapping.EventType.ACCOUNTS_RECEIVABLE;
            case STORE_CREDIT -> GlMapping.EventType.REFUND_LIABILITY;
            default -> GlMapping.EventType.TENDER_CLEARING;
        };
    }

    private void addPair(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                         LocalDate date, String reference, String description,
                         GlMapping.EventType debit, GlMapping.EventType credit, BigDecimal amount) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        add(entries, accounts, date, reference, description, debit, amount, BigDecimal.ZERO);
        add(entries, accounts, date, reference, description, credit, BigDecimal.ZERO, amount);
    }

    private void add(List<JournalEntryResponse> entries, Map<GlMapping.EventType, GlAccount> accounts,
                     LocalDate date, String reference, String description,
                     GlMapping.EventType eventType, BigDecimal debit, BigDecimal credit) {
        if (money(debit).signum() == 0 && money(credit).signum() == 0) {
            return;
        }
        GlAccount account = accounts.get(eventType);
        if (account == null) {
            throw new BusinessException("Missing GL account mapping for " + eventType);
        }
        entries.add(new JournalEntryResponse(date, reference, description, eventType.name(),
                account.getCode(), account.getName(),
                debit != null ? debit : BigDecimal.ZERO,
                credit != null ? credit : BigDecimal.ZERO));
    }

    private BigDecimal money(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal safeSum(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public String journalCsv(LocalDate from, LocalDate to) {
        StringBuilder csv = new StringBuilder();
        csv.append("Date,Reference,Description,Event,AccountCode,AccountName,Debit,Credit\n");
        for (JournalEntryResponse entry : journal(from, to)) {
            csv.append(escape(entry.getDate() != null ? entry.getDate().toString() : "")).append(',')
                    .append(escape(entry.getReference())).append(',')
                    .append(escape(entry.getDescription())).append(',')
                    .append(escape(entry.getEventType())).append(',')
                    .append(escape(entry.getAccountCode())).append(',')
                    .append(escape(entry.getAccountName())).append(',')
                    .append(entry.getDebit()).append(',')
                    .append(entry.getCredit()).append('\n');
        }
        return csv.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")
                ? "\"" + escaped + "\"" : escaped;
    }
}
