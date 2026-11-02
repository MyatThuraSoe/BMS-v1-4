package com.bms.service;

import com.bms.dto.request.GlAccountRequest;
import com.bms.dto.request.GlMappingRequest;
import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class GlService {

    private final GlAccountRepository accountRepository;
    private final GlMappingRepository mappingRepository;
    private final PurchaseRepository purchaseRepository;
    private final SaleRepository saleRepository;
    private final StockMovementRepository stockMovementRepository;

    public GlService(GlAccountRepository accountRepository,
                     GlMappingRepository mappingRepository,
                     PurchaseRepository purchaseRepository,
                     SaleRepository saleRepository,
                     StockMovementRepository stockMovementRepository) {
        this.accountRepository = accountRepository;
        this.mappingRepository = mappingRepository;
        this.purchaseRepository = purchaseRepository;
        this.saleRepository = saleRepository;
        this.stockMovementRepository = stockMovementRepository;
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

    /** Creates a sensible default chart of accounts and event mapping once. */
    public void ensureDefaults() {
        if (accountRepository.count() > 0) {
            return;
        }
        GlAccount cash = account("1000", "Cash", GlAccount.AccountType.ASSET);
        GlAccount receivable = account("1100", "Accounts Receivable", GlAccount.AccountType.ASSET);
        GlAccount inventory = account("1200", "Inventory Asset", GlAccount.AccountType.ASSET);
        GlAccount payable = account("2000", "Accounts Payable", GlAccount.AccountType.LIABILITY);
        GlAccount taxPayable = account("2100", "Sales Tax Payable", GlAccount.AccountType.LIABILITY);
        GlAccount revenue = account("4000", "Sales Revenue", GlAccount.AccountType.REVENUE);
        GlAccount cogs = account("5000", "Cost of Goods Sold", GlAccount.AccountType.EXPENSE);
        GlAccount shrinkage = account("5100", "Shrinkage & Spoilage", GlAccount.AccountType.EXPENSE);
        GlAccount freight = account("5200", "Freight & Duty", GlAccount.AccountType.EXPENSE);
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
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();
        if (end.isBefore(start)) {
            throw new BusinessException("Journal end date cannot be before its start date");
        }

        Map<GlMapping.EventType, GlAccount> accounts = mappingRepository.findAll().stream()
                .collect(Collectors.toMap(GlMapping::getEventType, GlMapping::getAccount,
                        (a, b) -> a));
        List<JournalEntryResponse> entries = new ArrayList<>();

        // Purchases: inventory asset debit / accounts payable credit.
        for (Purchase purchase : purchaseRepository.findForJournal(start, end)) {
            if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.ORDERED) {
                continue;
            }
            BigDecimal amount = purchase.getTotalAmount()
                    .multiply(purchase.getExchangeRate())
                    .setScale(2, RoundingMode.HALF_UP);
            addPair(entries, accounts, purchase.getPurchaseDate(),
                    purchase.getPurchaseNumber(), "Purchase received from "
                            + (purchase.getSupplier() != null ? purchase.getSupplier().getName() : "supplier"),
                    GlMapping.EventType.INVENTORY, GlMapping.EventType.ACCOUNTS_PAYABLE, amount);
        }

        // Sales: cash/AR debit, revenue + tax credit, discount debit, then COGS.
        for (Sale sale : saleRepository.findForJournal(start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            GlMapping.EventType debitAccount = sale.getSaleType() == Sale.SaleType.CREDIT
                    ? GlMapping.EventType.ACCOUNTS_RECEIVABLE : GlMapping.EventType.CASH;
            BigDecimal total = money(sale.getTotalAmount());
            BigDecimal discount = money(sale.getDiscountAmount())
                    .add(money(sale.getPromotionDiscount()))
                    .add(money(sale.getPriceListDiscount()));
            BigDecimal revenue = money(sale.getSubtotal());
            BigDecimal tax = money(sale.getTaxAmount());
            String reference = sale.getInvoiceNumber();
            LocalDate date = sale.getSaleDate().toLocalDate();

            add(entries, accounts, date, reference, "Sale total", debitAccount, total, BigDecimal.ZERO);
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

            BigDecimal cogs = sale.getItems().stream()
                    .map(item -> money(item.getCostPriceAtSale())
                            .multiply(BigDecimal.valueOf(item.getQuantity() != null ? item.getQuantity() : 0)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            if (cogs.signum() > 0) {
                addPair(entries, accounts, date, reference, "Cost of goods sold",
                        GlMapping.EventType.COST_OF_GOODS_SOLD, GlMapping.EventType.INVENTORY, cogs);
            }
        }

        // Stock write-offs / count variances / RTV: expense debit, inventory credit.
        for (StockMovement movement : stockMovementRepository.findForJournal(
                start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            BigDecimal valuation = money(movement.getValuationAmount());
            if (valuation.signum() == 0) {
                continue;
            }
            LocalDate date = movement.getMovementDate().toLocalDate();
            String reference = "MOV-" + movement.getId();
            if ("COUNT_CORRECTION".equalsIgnoreCase(movement.getReasonCode())
                    || "MISCOUNT".equalsIgnoreCase(movement.getReasonCode())) {
                // Neutral inventory correction: no P&L impact.
                continue;
            }
            if (valuation.signum() < 0) {
                addPair(entries, accounts, date, reference,
                        movement.getDescription() != null ? movement.getDescription() : "Stock write-off",
                        GlMapping.EventType.OPERATING_EXPENSE, GlMapping.EventType.INVENTORY,
                        valuation.abs());
            } else {
                addPair(entries, accounts, date, reference,
                        movement.getDescription() != null ? movement.getDescription() : "Stock adjustment",
                        GlMapping.EventType.INVENTORY, GlMapping.EventType.OPERATING_EXPENSE,
                        valuation);
            }
        }

        entries.sort(Comparator.comparing(JournalEntryResponse::getDate)
                .thenComparing(JournalEntryResponse::getReference)
                .thenComparing(JournalEntryResponse::getAccountCode));
        return entries;
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
        GlAccount account = accounts.get(eventType);
        if (account == null) {
            return;
        }
        entries.add(new JournalEntryResponse(date, reference, description, eventType.name(),
                account.getCode(), account.getName(),
                debit != null ? debit : BigDecimal.ZERO,
                credit != null ? credit : BigDecimal.ZERO));
    }

    private BigDecimal money(BigDecimal value) {
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
