package com.bms.service;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.dto.request.ReceivePurchaseRequest;
import com.bms.dto.request.ReturnToVendorRequest;
import com.bms.dto.response.PurchaseItemResponse;
import com.bms.dto.response.PurchaseResponse;
import com.bms.dto.response.SupplierStatsResponse;
import com.bms.dto.response.SupplierTopProductResponse;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class PurchaseService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PurchaseService.class);

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private PurchaseItemRepository purchaseItemRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductPriceHistoryRepository priceHistoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private LocationService locationService;

    @Autowired
    private com.bms.repository.ExpenseRepository expenseRepository;

    @Autowired
    private ManagerApprovalService managerApprovalService;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private PurchasePaymentService purchasePaymentService;

    @Autowired
    private PeriodGuard periodGuard;

    @Autowired
    private ShopTimeService shopTimeService;

    public Page<PurchaseResponse> getAllPurchases(Pageable pageable) {
        return purchaseRepository.findActivePurchases(pageable).map(this::mapToResponse);
    }

    public List<PurchaseResponse> getOpenPurchases() {
        return purchaseRepository.findOpenForReceiving(
                        List.of(Purchase.ReceivingStatus.ORDERED, Purchase.ReceivingStatus.PARTIAL))
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    public PurchaseResponse getPurchaseById(Long id) {
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + id);
        }
        return mapToResponse(purchase);
    }

    public PurchaseResponse getPurchaseByNumber(String purchaseNumber) {
        Purchase purchase = purchaseRepository.findByPurchaseNumber(purchaseNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseNumber));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseNumber);
        }
        return mapToResponse(purchase);
    }

    public PurchaseResponse createPurchase(PurchaseCreateRequest request, Long userId) {
        periodGuard.assertOpen(LocalDate.parse(request.getPurchaseDate(), DateTimeFormatter.ISO_LOCAL_DATE));
        Supplier supplier = null;
        if (request.getSupplierId() != null) {
            supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + request.getSupplierId()));
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Purchase must have at least one item");
        }

        Purchase purchase = new Purchase();
        purchase.setPurchaseNumber(generatePurchaseNumber());
        purchase.setSupplier(supplier);
        purchase.setPurchaseDate(LocalDate.parse(request.getPurchaseDate(), DateTimeFormatter.ISO_LOCAL_DATE));
        purchase.setCreatedBy(userId);
        purchase.setNotes(request.getNotes());
        purchase.setCurrencyCode(normalizeCurrency(request.getCurrencyCode()));
        purchase.setExchangeRate(normalizeExchangeRate(request.getExchangeRate()));
        purchase.setTaxRecoverable(request.getTaxRecoverable() != null && request.getTaxRecoverable());

        for (PurchaseCreateRequest.PurchaseItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitCost(itemRequest.getUnitCost());
            item.setTotalCost(itemRequest.getUnitCost().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
            item.setExchangeRate(purchase.getExchangeRate());
            purchase.getItems().add(item);
        }

        applyExpenses(purchase, request.getExpenses());
        applyTotals(purchase, request);

        Purchase savedPurchase = purchaseRepository.saveAndFlush(purchase);
        applyLandedCosts(savedPurchase);

        boolean receiveNow = request.getReceiveNow() == null || request.getReceiveNow();
        if (receiveNow) {
            receiveAll(savedPurchase, userId);
        } else {
            savedPurchase.setReceivingStatus(Purchase.ReceivingStatus.ORDERED);
            purchaseRepository.save(savedPurchase);
        }
        purchasePaymentService.refreshPaymentStatus(savedPurchase);

        auditLogService.logAction(userId, "PURCHASE_CREATE",
                "Purchase created: " + savedPurchase.getPurchaseNumber(),
                "Purchase", savedPurchase.getId(), null, savedPurchase.toString());

        return mapToResponse(savedPurchase);
    }

    // ------------------------------------------------------------------
    // Landed cost / totals
    // ------------------------------------------------------------------

    private void applyTotals(Purchase purchase, PurchaseCreateRequest request) {
        BigDecimal subtotal = purchase.getItems().stream()
                .map(PurchaseItem::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discountAmount = request.getDiscountAmount() != null
                ? request.getDiscountAmount() : BigDecimal.ZERO;
        if (discountAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Discount amount cannot be negative");
        }
        if (discountAmount.compareTo(subtotal) > 0) {
            throw new BusinessException("Discount amount cannot exceed subtotal");
        }
        BigDecimal taxAmount = request.getTaxAmount() != null
                ? request.getTaxAmount() : BigDecimal.ZERO;
        if (taxAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Tax amount cannot be negative");
        }

        BigDecimal expenseTotal = purchase.getLandedCostTotal();
        BigDecimal totalAmount = subtotal.subtract(discountAmount).add(taxAmount).add(expenseTotal);
        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Total cannot be negative; discount exceeds subtotal");
        }

        purchase.setSubtotal(subtotal);
        purchase.setDiscountAmount(discountAmount);
        purchase.setTaxAmount(taxAmount);
        purchase.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));
        // O3: paymentStatus is derived from recorded payments, never hand-set.
    }

    private void applyExpenses(Purchase purchase, List<PurchaseCreateRequest.ExpenseRequest> requests) {
        purchase.getExpenses().clear();
        BigDecimal total = BigDecimal.ZERO;
        if (requests != null) {
            for (PurchaseCreateRequest.ExpenseRequest request : requests) {
                PurchaseExpense expense = new PurchaseExpense();
                expense.setPurchase(purchase);
                expense.setDescription(request.getDescription().trim());
                expense.setAmount(request.getAmount());
                expense.setType(parseExpenseType(request.getType()));
                expense.setAllocationMethod(parseAllocationMethod(request.getAllocationMethod()));
                purchase.getExpenses().add(expense);
                total = total.add(request.getAmount());
            }
        }
        purchase.setLandedCostTotal(total);
    }

    /** Allocates discount, non-recoverable tax and expenses across the PO lines. */
    private void applyLandedCosts(Purchase purchase) {
        LandedPlan plan = buildLandedPlan(purchase);
        for (PurchaseItem item : purchase.getItems()) {
            int qty = plan.orderedQty.getOrDefault(item.getId(), 0);
            BigDecimal net = plan.netLineValue.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal expenseSum = BigDecimal.ZERO;
            for (Map<Long, BigDecimal> shares : plan.expenseAlloc) {
                expenseSum = expenseSum.add(shares.getOrDefault(item.getId(), BigDecimal.ZERO));
            }
            BigDecimal landed = qty > 0
                    ? net.add(expenseSum).divide(BigDecimal.valueOf(qty), CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP)
                    : item.getUnitCost();
            item.setLandedUnitCost(landed.setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
            item.setBaseUnitCost(landed.multiply(plan.rate).setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
            item.setExchangeRate(plan.rate);
        }
        purchaseRepository.save(purchase);
    }

    /**
     * Per-line landed-cost components in invoice currency: goods value net of
     * the allocated header discount and (when non-recoverable, D2) tax, plus
     * each expense's allocation. Recomputed deterministically from the ordered
     * lines so receipt-time capitalisation needs no extra persisted state.
     */
    private static final class LandedPlan {
        final Map<Long, BigDecimal> netLineValue = new LinkedHashMap<>();
        final List<Map<Long, BigDecimal>> expenseAlloc = new ArrayList<>();
        final Map<Long, Integer> orderedQty = new LinkedHashMap<>();
        BigDecimal rate = BigDecimal.ONE;
    }

    private LandedPlan buildLandedPlan(Purchase purchase) {
        LandedPlan plan = new LandedPlan();
        plan.rate = purchase.getExchangeRate();
        List<LandedCostAllocator.Line> lines = new ArrayList<>();
        for (PurchaseItem item : purchase.getItems()) {
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            plan.orderedQty.put(item.getId(), qty);
            lines.add(new LandedCostAllocator.Line(item.getId(), item.getUnitCost(), qty,
                    item.getProduct().getWeight()));
        }
        Map<Long, BigDecimal> discountShares =
                LandedCostAllocator.allocateByValue(lines, purchase.getDiscountAmount());
        Map<Long, BigDecimal> taxShares = purchase.isTaxRecoverable()
                ? Collections.emptyMap()
                : LandedCostAllocator.allocateByValue(lines, purchase.getTaxAmount());
        List<LandedCostAllocator.Expense> expenses = purchase.getExpenses().stream()
                .map(expense -> new LandedCostAllocator.Expense(
                        expense.getAmount(), expense.getAllocationMethod()))
                .collect(Collectors.toList());
        plan.expenseAlloc.addAll(LandedCostAllocator.allocateExpenseAmounts(lines, expenses));
        for (LandedCostAllocator.Line line : lines) {
            BigDecimal net = line.getTotalValue()
                    .subtract(discountShares.getOrDefault(line.getId(), BigDecimal.ZERO))
                    .add(taxShares.getOrDefault(line.getId(), BigDecimal.ZERO));
            plan.netLineValue.put(line.getId(), net);
        }
        return plan;
    }

    /**
     * Expense amount (invoice currency) capitalised for a line as its received
     * quantity moves from {@code before} to {@code after}: proportional to the
     * ordered quantity, capped so cumulative capitalisation never exceeds the
     * allocation. Over-receipt therefore absorbs only the remainder (C6).
     */
    private BigDecimal expenseCapitalisedBetween(LandedPlan plan, Long itemId, int before, int after) {
        int ordered = plan.orderedQty.getOrDefault(itemId, 0);
        if (ordered <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<Long, BigDecimal> shares : plan.expenseAlloc) {
            BigDecimal alloc = shares.getOrDefault(itemId, BigDecimal.ZERO);
            if (alloc.signum() == 0) {
                continue;
            }
            total = total.add(cappedExpense(alloc, after, ordered)
                    .subtract(cappedExpense(alloc, before, ordered)));
        }
        return total;
    }

    private BigDecimal cappedExpense(BigDecimal alloc, int received, int ordered) {
        if (received <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal proportional = alloc.multiply(BigDecimal.valueOf(received))
                .divide(BigDecimal.valueOf(ordered), 6, RoundingMode.HALF_UP);
        return proportional.min(alloc);
    }

    /**
     * C5/C6: once a PO is closed (fully received, over-received, or remainder
     * cancelled) the payable must reflect the goods actually received and only
     * the freight actually capitalised. Any unabsorbed expense becomes purchase
     * variance (D3) and is expensed to the P&L.
     */
    private void recomputeTotalsOnClose(Purchase purchase, Long userId) {
        LandedPlan plan = buildLandedPlan(purchase);
        BigDecimal receivedSubtotal = BigDecimal.ZERO;
        BigDecimal capitalisedExpense = BigDecimal.ZERO;
        for (PurchaseItem item : purchase.getItems()) {
            int received = item.getReceivedQuantity();
            receivedSubtotal = receivedSubtotal.add(
                    item.getUnitCost().multiply(BigDecimal.valueOf(received)));
            capitalisedExpense = capitalisedExpense.add(
                    expenseCapitalisedBetween(plan, item.getId(), 0, received));
        }
        BigDecimal expenseTotal = purchase.getExpenses().stream()
                .map(PurchaseExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discount = scaledToReceived(purchase.getDiscountAmount(),
                receivedSubtotal, purchase.getSubtotal());
        BigDecimal tax = scaledToReceived(purchase.getTaxAmount(),
                receivedSubtotal, purchase.getSubtotal());
        purchase.setTotalAmount(receivedSubtotal.subtract(discount).add(tax).add(capitalisedExpense)
                .setScale(2, RoundingMode.HALF_UP));

        BigDecimal variance = expenseTotal.subtract(capitalisedExpense).max(BigDecimal.ZERO)
                .multiply(purchase.getExchangeRate()).setScale(2, RoundingMode.HALF_UP);
        purchase.setVarianceAmount(variance);
        if (variance.signum() > 0) {
            Expense varianceExpense = new Expense();
            varianceExpense.setCategory(Expense.ExpenseCategory.PURCHASE_VARIANCE);
            varianceExpense.setDescription("Unabsorbed landed cost on purchase "
                    + purchase.getPurchaseNumber());
            varianceExpense.setAmount(variance);
            varianceExpense.setExpenseDate(shopTimeService.today());
            varianceExpense.setCreatedBy(userId);
            expenseRepository.save(varianceExpense);
            ledgerPostingService.postExpense(varianceExpense);
        }
        // O3: the payable changed with the closed totals; re-derive the status.
        purchasePaymentService.refreshPaymentStatus(purchase);
    }

    /** Scales a header discount/tax to the received value, never above the header amount. */
    private BigDecimal scaledToReceived(BigDecimal headerAmount, BigDecimal receivedSubtotal, BigDecimal subtotal) {
        if (headerAmount == null || headerAmount.signum() == 0
                || subtotal == null || subtotal.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal scaled = headerAmount.multiply(receivedSubtotal)
                .divide(subtotal, 2, RoundingMode.HALF_UP);
        return scaled.min(headerAmount);
    }

    private String normalizeCurrency(String currencyCode) {
        String code = currencyCode != null ? currencyCode.trim().toUpperCase() : "USD";
        if (code.length() > 10) {
            throw new BusinessException("Currency code is too long");
        }
        return code;
    }

    private BigDecimal normalizeExchangeRate(BigDecimal exchangeRate) {
        BigDecimal rate = exchangeRate != null ? exchangeRate : BigDecimal.ONE;
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Exchange rate must be greater than zero");
        }
        return rate;
    }

    private PurchaseExpense.ExpenseType parseExpenseType(String value) {
        try {
            return PurchaseExpense.ExpenseType.valueOf(
                    value != null ? value.trim().toUpperCase() : "FREIGHT");
        } catch (Exception ex) {
            throw new BusinessException("Invalid expense type: " + value);
        }
    }

    private PurchaseExpense.AllocationMethod parseAllocationMethod(String value) {
        try {
            return PurchaseExpense.AllocationMethod.valueOf(
                    value != null ? value.trim().toUpperCase() : "VALUE");
        } catch (Exception ex) {
            throw new BusinessException("Invalid allocation method: " + value);
        }
    }

    // ------------------------------------------------------------------
    // Receiving (full, partial, blind, over-shipment, quarantine)
    // ------------------------------------------------------------------

    private void receiveAll(Purchase purchase, Long userId) {
        Location shop = locationService.getDefault(Location.LocationType.SHOP);
        for (PurchaseItem item : purchase.getItems()) {
            int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
            if (quantity <= 0) {
                continue;
            }
            receiveUnits(purchase, item, quantity, 0, shop, userId, null);
        }
        purchase.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
        purchase.setReceivedAt(shopTimeService.now());
        purchase.setReceivedBy(userId);
        purchaseRepository.save(purchase);
    }

    public PurchaseResponse receivePurchase(Long purchaseId, ReceivePurchaseRequest request, Long userId) {
        periodGuard.assertOpen(shopTimeService.now());
        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }
        if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.RECEIVED) {
            throw new BusinessException("Purchase " + purchase.getPurchaseNumber() + " is already fully received");
        }

        Map<Long, PurchaseItem> itemsById = purchase.getItems().stream()
                .collect(Collectors.toMap(PurchaseItem::getId, item -> item));
        Set<Long> seen = new HashSet<>();
        boolean overShipment = false;
        for (ReceivePurchaseRequest.ReceiveItem entry : request.getItems()) {
            if (!seen.add(entry.getPurchaseItemId())) {
                throw new BusinessException("Duplicate purchase item in receive request: " + entry.getPurchaseItemId());
            }
            PurchaseItem item = itemsById.get(entry.getPurchaseItemId());
            if (item == null) {
                throw new BusinessException("Purchase item " + entry.getPurchaseItemId()
                        + " does not belong to this purchase");
            }
            int received = entry.getReceivedQuantity() != null ? entry.getReceivedQuantity() : 0;
            int quarantine = entry.getQuarantineQuantity();
            if (received < 0 || quarantine < 0) {
                throw new BusinessException("Received quantities cannot be negative");
            }
            if (quarantine > received) {
                throw new BusinessException("Quarantine quantity cannot exceed the received quantity");
            }
            int newTotal = item.getReceivedQuantity() + received;
            if (newTotal > item.getQuantity()) {
                overShipment = true;
            }
        }

        if (overShipment) {
            verifyManagerOverride(request.getManagerUsername(), request.getManagerPassword(), userId);
        }

        Location quarantineLocation = locationService.getDefault(Location.LocationType.QUARANTINE);
        Location shop = locationService.getDefault(Location.LocationType.SHOP);
        boolean anyReceived = false;

        for (ReceivePurchaseRequest.ReceiveItem entry : request.getItems()) {
            PurchaseItem item = itemsById.get(entry.getPurchaseItemId());
            int received = entry.getReceivedQuantity() != null ? entry.getReceivedQuantity() : 0;
            if (received == 0) {
                continue;
            }
            Location destination = entry.getLocationId() != null
                    ? locationService.getLocation(entry.getLocationId()) : shop;
            receiveUnits(purchase, item, received, entry.getQuarantineQuantity(),
                    destination, userId, quarantineLocation);
            anyReceived = true;
        }

        if (!anyReceived && !request.getCancelRemainder()) {
            throw new BusinessException("Nothing was received. Enter received quantities or cancel the remainder.");
        }

        boolean fullyReceived = purchase.getItems().stream()
                .allMatch(item -> item.getReceivedQuantity() >= item.getQuantity());
        boolean cancelRemainder = request.getCancelRemainder() && !fullyReceived;
        if (fullyReceived || cancelRemainder) {
            purchase.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
            recomputeTotalsOnClose(purchase, userId);
        } else {
            purchase.setReceivingStatus(Purchase.ReceivingStatus.PARTIAL);
        }
        purchase.setReceivedAt(shopTimeService.now());
        purchase.setReceivedBy(userId);
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            purchase.setNotes((purchase.getNotes() != null ? purchase.getNotes() + "\n" : "")
                    + request.getNotes());
        }
        purchaseRepository.save(purchase);

        auditLogService.logAction(userId, "PURCHASE_RECEIVE",
                "Purchase received: " + purchase.getPurchaseNumber() + " (" + purchase.getReceivingStatus() + ")",
                "Purchase", purchase.getId(), null, null);
        return mapToResponse(purchase);
    }

    private void receiveUnits(Purchase purchase, PurchaseItem item, int received, int quarantine,
                              Location destination, Long userId, Location quarantineLocation) {
        if (destination == null) {
            throw new BusinessException("No receiving location configured for the destination.");
        }
        if (quarantine > 0 && quarantineLocation == null) {
            throw new BusinessException("No quarantine location configured.");
        }

        Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + item.getProduct().getId()));
        User user = userRepository.findById(userId).orElse(null);

        int sellable = received - quarantine;
        // C5/C6: capitalise what was actually received — goods net of discount
        // and non-recoverable tax, plus only the proportional share of expenses
        // (capped so over-receipt never absorbs more than the expense amount).
        LandedPlan plan = buildLandedPlan(purchase);
        int ordered = item.getQuantity() != null ? item.getQuantity() : 0;
        int before = item.getReceivedQuantity();
        BigDecimal netLineValue = plan.netLineValue.getOrDefault(item.getId(), BigDecimal.ZERO);
        BigDecimal goodsNow = ordered > 0
                ? netLineValue.multiply(BigDecimal.valueOf(received))
                        .divide(BigDecimal.valueOf(ordered), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal expenseNow = expenseCapitalisedBetween(plan, item.getId(), before, before + received);
        BigDecimal valuation = goodsNow.add(expenseNow).multiply(purchase.getExchangeRate())
                .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal unitCost = received > 0
                ? valuation.divide(BigDecimal.valueOf(received), 6, RoundingMode.HALF_UP)
                : item.getBaseUnitCost();

        int oldStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        // C1: the average base is EVERY unit the business owns before this
        // receipt (shop + warehouse + quarantine + transit), not shop alone.
        // The added quantity is every unit received, including quarantined
        // ones, because the business paid for them.
        BigDecimal oldCostPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        BigDecimal sellableValuation = valuation.multiply(BigDecimal.valueOf(sellable))
                .divide(BigDecimal.valueOf(received), CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);

        // P1/O8: post the valued receipt BEFORE mutating stock so the ledger derives
        // the new weighted average from the true pre-receipt owned balance. This is
        // now the sole writer of product.costPrice.
        InventoryLedger posting = ledgerPostingService.postInventory(
                product, InventoryLedger.EventType.RECEIPT, "PURCHASE", purchase.getId(),
                item.getId() + ":" + before, received, unitCost, valuation,
                destination != null ? destination.getId() : null,
                "Received " + received + " unit(s) from purchase " + purchase.getPurchaseNumber(),
                shopTimeService.now(), userId);
        BigDecimal newWeightedCost = posting.getUnitCostAfter();

        if (sellable > 0) {
            if (destination.getType() == Location.LocationType.SHOP) {
                product.setStockQuantity(oldStock + sellable);
            } else {
                locationService.addStock(product, destination, sellable);
            }
            recordMovement(purchase, item, product, StockMovement.MovementType.IN, sellable,
                    StockMovement.ReferenceType.RECEIVING, destination.getCode(),
                    "Received " + sellable + " unit(s) of " + product.getName()
                            + " from purchase " + purchase.getPurchaseNumber(),
                    sellableValuation, user);
        }
        if (quarantine > 0) {
            locationService.addStock(product, quarantineLocation, quarantine);
            recordMovement(purchase, item, product, StockMovement.MovementType.IN, quarantine,
                    StockMovement.ReferenceType.RECEIVING, quarantineLocation.getCode(),
                    "Quarantined " + quarantine + " unit(s) of " + product.getName()
                            + " from purchase " + purchase.getPurchaseNumber(),
                    valuation.subtract(sellableValuation), user);
        }

        productRepository.save(product);

        if (oldCostPrice.compareTo(newWeightedCost) != 0) {
            ProductPriceHistory history = new ProductPriceHistory();
            history.setProduct(product);
            history.setOldPrice(oldCostPrice);
            history.setNewPrice(newWeightedCost);
            history.setPriceType(ProductPriceHistory.PriceType.COST);
            history.setSource(ProductPriceHistory.Source.PURCHASE_RECEIPT);
            history.setPurchaseId(purchase.getId());
            history.setSupplierId(purchase.getSupplier() != null ? purchase.getSupplier().getId() : null);
            history.setChangedBy(user);
            history.setPurchaseSupplierName(purchase.getSupplier() != null
                    ? purchase.getSupplier().getName() : null);
            history.setPurchaseQuantity(received);
            history.setPurchaseUnitPrice(unitCost);
            history.setStockQtyAtChange(locationService.totalOwnedUnits(product));
            history.setInventoryValueDelta(valuation);
            priceHistoryRepository.save(history);
        }

        item.setReceivedQuantity(item.getReceivedQuantity() + received);
        purchaseItemRepository.save(item);
    }

    private void recordMovement(Purchase purchase, PurchaseItem item, Product product,
                                StockMovement.MovementType type,
                                int quantity, StockMovement.ReferenceType referenceType, String location,
                                String description, BigDecimal valuation, User user) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(purchase.getId());
        movement.setPurchaseItemId(item.getId());
        movement.setDescription(description);
        movement.setLocation(location);
        movement.setValuationAmount(valuation);
        movement.setCreatedBy(user);
        movement.setMovementDate(shopTimeService.now());
        stockMovementRepository.save(movement);
    }

    /**
     * M2: the over-shipment override goes through the shared approval path, so a wrong
     * manager password here counts against the same lockout as login and every result is
     * audited.
     */
    private void verifyManagerOverride(String username, String password, Long cashierId) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(
                    "Received quantity exceeds the ordered quantity. Manager credentials are required.");
        }
        managerApprovalService.verify(username, password, ManagerApprovalService.Purpose.OVER_SHIPMENT,
                cashierId);
    }

    // ------------------------------------------------------------------
    // Return to vendor
    // ------------------------------------------------------------------

    public PurchaseResponse returnToVendor(Long purchaseId, ReturnToVendorRequest request, Long userId) {
        periodGuard.assertOpen(shopTimeService.now());
        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }

        Map<Long, PurchaseItem> itemsById = purchase.getItems().stream()
                .collect(Collectors.toMap(PurchaseItem::getId, item -> item));
        User user = userRepository.findById(userId).orElse(null);

        BigDecimal totalCredit = BigDecimal.ZERO;
        for (ReturnToVendorRequest.ReturnItem entry : request.getItems()) {
            PurchaseItem item = itemsById.get(entry.getPurchaseItemId());
            if (item == null) {
                throw new BusinessException("Purchase item " + entry.getPurchaseItemId()
                        + " does not belong to this purchase");
            }
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + item.getProduct().getId()));

            int available = item.getReceivedQuantity() - item.getReturnedQuantity();
            // Received-but-not-returned units may already have been sold; cap at
            // every unit the business still owns (shop + all locations), not just
            // shop stock — quarantined units are still owned and returnable.
            int physicallyAvailable = Math.min(available, locationService.totalOwnedUnits(product));
            if (entry.getQuantity() > physicallyAvailable) {
                throw new BusinessException("Only " + physicallyAvailable + " unit(s) of "
                        + item.getProduct().getName() + " can still be returned");
            }

            int ownedBefore = locationService.totalOwnedUnits(product);
            BigDecimal oldCostPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;

            // P1/O8: post the valued RTV BEFORE removing stock; sole writer of cost.
            // Removed units leave at this line's own landed base cost, averaged over
            // ALL owned units, with the C8 negative-value guard raising an alert.
            InventoryLedger posting = ledgerPostingService.postInventory(
                    product, InventoryLedger.EventType.RTV, "PURCHASE_RTV", purchase.getId(),
                    item.getId() + ":" + item.getReturnedQuantity(), -entry.getQuantity(),
                    item.getBaseUnitCost(),
                    item.getBaseUnitCost().multiply(BigDecimal.valueOf(entry.getQuantity())).negate(),
                    null, request.getReason(), shopTimeService.now(), userId,
                    message -> recordCostReconciliationAlert(userId, product, purchase, message));
            BigDecimal newCostPrice = posting.getUnitCostAfter();

            Map<String, Integer> removedByLocation = new LinkedHashMap<>();
            removeUnitsFromReceiptLocations(purchase, item, product, entry.getQuantity(),
                    request.isOverrideReserved(), request.getReason(), userId, removedByLocation);
            for (Map.Entry<String, Integer> removedEntry : removedByLocation.entrySet()) {
                int removed = removedEntry.getValue();
                if (removed <= 0) {
                    continue;
                }
                String locationCode = removedEntry.getKey();
                String description = "RTV from shop floor";
                if (locationCode != null && !locationCode.equalsIgnoreCase("SHOP")) {
                    Location location = locationService.findByCode(locationCode).orElse(null);
                    if (location != null && location.getType() == Location.LocationType.QUARANTINE) {
                        description = "RTV from quarantine";
                    } else if (location != null) {
                        description = "RTV from " + location.getCode();
                    }
                }
                recordReturnMovement(purchase, product, removed, locationCode,
                        description, item, user);
            }

            // Stock removed above; cost already revalued by the ledger posting.
            productRepository.save(product);

            BigDecimal credit = rtvCreditAmount(purchase, item, entry.getQuantity());
            totalCredit = totalCredit.add(credit);

            ProductPriceHistory history = new ProductPriceHistory();
            history.setProduct(product);
            history.setOldPrice(oldCostPrice);
            history.setNewPrice(newCostPrice);
            history.setPriceType(ProductPriceHistory.PriceType.COST);
            history.setSource(ProductPriceHistory.Source.RTV);
            history.setReason(request.getReason());
            history.setPurchaseId(purchase.getId());
            history.setSupplierId(purchase.getSupplier() != null ? purchase.getSupplier().getId() : null);
            history.setChangedBy(user);
            history.setPurchaseSupplierName(purchase.getSupplier() != null
                    ? purchase.getSupplier().getName() : null);
            history.setPurchaseQuantity(-entry.getQuantity());
            history.setPurchaseUnitPrice(item.getBaseUnitCost());
            history.setStockQtyAtChange(ownedBefore - entry.getQuantity());
            history.setInventoryValueDelta(item.getBaseUnitCost()
                    .multiply(BigDecimal.valueOf(entry.getQuantity())).negate());
            priceHistoryRepository.save(history);

            item.setReturnedQuantity(item.getReturnedQuantity() + entry.getQuantity());
            purchaseItemRepository.save(item);
        }

        // D1: supplier credit reduces what is owed on this purchase.
        purchase.setReturnedAmount(purchase.getReturnedAmount().add(totalCredit));
        purchasePaymentService.refreshPaymentStatus(purchase);
        purchaseRepository.save(purchase);

        auditLogService.logAction(userId, "PURCHASE_RTV",
                "Returned goods to vendor for purchase: " + purchase.getPurchaseNumber(),
                "Purchase", purchase.getId(), null, request.getReason());
        return mapToResponse(purchase);
    }

    /**
     * Quarantine units still attributable to this purchase line: its own
     * quarantine receipts minus what was already returned or released
     * (transferred out), capped by what is physically in the bucket.
     */
    private int quarantineAvailableForItem(Purchase purchase, PurchaseItem item,
                                           Product product, Location quarantine) {
        int received = 0;
        for (StockMovement movement : stockMovementRepository.findByReferenceAndPurchaseItem(
                StockMovement.ReferenceType.RECEIVING, purchase.getId(), item.getId())) {
            if (movement.getMovementType() == StockMovement.MovementType.IN
                    && quarantine.getCode().equalsIgnoreCase(movement.getLocation())) {
                received += movement.getQuantity();
            }
        }
        int left = 0;
        for (StockMovement movement : stockMovementRepository.findByPurchaseItemId(item.getId())) {
            if (!quarantine.getCode().equalsIgnoreCase(movement.getLocation())) {
                continue;
            }
            if (movement.getReferenceType() == StockMovement.ReferenceType.RTV
                    && movement.getMovementType() == StockMovement.MovementType.OUT) {
                left += movement.getQuantity();
            }
            if (movement.getReferenceType() == StockMovement.ReferenceType.TRANSFER
                    && movement.getMovementType() == StockMovement.MovementType.TRANSFER_OUT) {
                left += movement.getQuantity();
            }
        }
        int scoped = Math.max(0, received - left);
        return Math.min(scoped, locationService.quantityIn(quarantine, product));
    }

    private void removeUnitsFromReceiptLocations(Purchase purchase, PurchaseItem item, Product product,
                                                int quantity, boolean overrideReserved,
                                                String reason, Long userId) {
        removeUnitsFromReceiptLocations(purchase, item, product, quantity, overrideReserved,
                reason, userId, new LinkedHashMap<>());
    }

    private void removeUnitsFromReceiptLocations(Purchase purchase, PurchaseItem item, Product product,
                                                int quantity, boolean overrideReserved,
                                                String reason, Long userId,
                                                Map<String, Integer> removedByLocation) {
        if (quantity <= 0) {
            return;
        }

        Map<String, Integer> receivedByLocation = new HashMap<>();
        for (StockMovement movement : stockMovementRepository.findByReferenceAndPurchaseItem(
                StockMovement.ReferenceType.RECEIVING, purchase.getId(), item.getId())) {
            if (movement.getMovementType() == StockMovement.MovementType.IN
                    && movement.getLocation() != null && movement.getQuantity() != null) {
                receivedByLocation.merge(movement.getLocation(), movement.getQuantity(), Integer::sum);
            }
        }

        Map<String, Integer> removedAlreadyByLocation = new HashMap<>();
        for (StockMovement movement : stockMovementRepository.findByPurchaseItemId(item.getId())) {
            if (movement.getLocation() == null || movement.getQuantity() == null) {
                continue;
            }
            boolean isReturnRemoval = movement.getReferenceType() == StockMovement.ReferenceType.RTV
                    && movement.getMovementType() == StockMovement.MovementType.OUT;
            boolean isPurchaseReversal = movement.getReferenceType() == StockMovement.ReferenceType.PURCHASE
                    && movement.getMovementType() == StockMovement.MovementType.OUT;
            boolean isTransferOut = movement.getReferenceType() == StockMovement.ReferenceType.TRANSFER
                    && movement.getMovementType() == StockMovement.MovementType.TRANSFER_OUT;
            if (isReturnRemoval || isPurchaseReversal || isTransferOut) {
                removedAlreadyByLocation.merge(movement.getLocation(), movement.getQuantity(), Integer::sum);
            }
        }

        List<String> orderedCodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : receivedByLocation.keySet()) {
            Location location = locationService.findByCode(code).orElse(null);
            if (location != null && location.getType() == Location.LocationType.QUARANTINE) {
                orderedCodes.add(code);
                seen.add(code);
            }
        }
        List<String> nonShopCodes = receivedByLocation.keySet().stream()
                .filter(code -> !seen.contains(code))
                .filter(code -> {
                    Location location = locationService.findByCode(code).orElse(null);
                    return location != null && location.getType() != Location.LocationType.SHOP;
                })
                .sorted(Comparator.comparing(code -> locationService.findByCode(code)
                        .map(Location::getId).orElse(Long.MAX_VALUE)))
                .toList();
        orderedCodes.addAll(nonShopCodes);
        List<String> shopCodes = receivedByLocation.keySet().stream()
                .filter(code -> !seen.contains(code))
                .filter(code -> {
                    Location location = locationService.findByCode(code).orElse(null);
                    return location == null || location.getType() == Location.LocationType.SHOP;
                })
                .toList();
        orderedCodes.addAll(shopCodes);

        int remaining = quantity;
        for (String code : orderedCodes) {
            if (remaining <= 0) {
                break;
            }
            int received = receivedByLocation.getOrDefault(code, 0);
            int alreadyRemoved = removedAlreadyByLocation.getOrDefault(code, 0);
            int available = Math.max(0, received - alreadyRemoved);
            if (available <= 0) {
                continue;
            }
            int take = Math.min(remaining, available);
            Location location = locationService.findByCode(code).orElse(null);
            if (location == null) {
                continue;
            }
            if (location.getType() == Location.LocationType.SHOP) {
                locationService.removeShopStock(product, take, overrideReserved, reason, userId);
            } else {
                locationService.removeStock(product, location, take);
            }
            removedByLocation.merge(code, take, Integer::sum);
            remaining -= take;
        }

        if (remaining > 0) {
            int shopUnits = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            if (shopUnits <= 0) {
                throw new BusinessException("Only " + (quantity - remaining) + " unit(s) of "
                        + product.getName() + " are available to return");
            }
            int take = Math.min(remaining, shopUnits);
            locationService.removeShopStock(product, take, overrideReserved, reason, userId);
            String shopCode = locationService.getDefault(Location.LocationType.SHOP).getCode();
            removedByLocation.merge(shopCode, take, Integer::sum);
            remaining -= take;
        }

        if (remaining > 0) {
            throw new BusinessException("Only " + (quantity - remaining) + " unit(s) of "
                    + product.getName() + " are available to return");
        }
    }

    /**
     * Supplier credit for returned units (D1), in INVOICE currency so it is
     * consistent with {@code totalAmount} and therefore with what the supplier
     * actually invoiced (O3): the line's landed invoice-currency unit cost plus
     * the pro-rata share of recoverable invoice tax (by line value).
     *
     * <p>{@code landedUnitCost} is already in invoice currency and already carries
     * the capitalised discount, non-recoverable tax and freight share, so those are
     * credited exactly once. {@code baseUnitCost} is that same value converted to
     * the shop base currency: using it here made every foreign-currency return
     * reduce the payable by the exchange-rate factor as well, which could take the
     * whole remaining invoice balance with it.
     */
    private BigDecimal rtvCreditAmount(Purchase purchase, PurchaseItem item, int quantity) {
        BigDecimal credit = item.getLandedUnitCost()
                .multiply(BigDecimal.valueOf(quantity));
        if (Boolean.TRUE.equals(purchase.isTaxRecoverable())) {
            BigDecimal tax = purchase.getTaxAmount();
            BigDecimal invoiceSubtotal = purchase.getItems().stream()
                    .map(i -> i.getLandedUnitCost().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (tax != null && tax.signum() > 0 && invoiceSubtotal.signum() > 0) {
                BigDecimal lineValue = item.getLandedUnitCost().multiply(BigDecimal.valueOf(quantity));
                credit = credit.add(tax.multiply(lineValue)
                        .divide(invoiceSubtotal, 2, RoundingMode.HALF_UP));
            }
        }
        return credit.setScale(2, RoundingMode.HALF_UP);
    }

    private void recordReturnMovement(Purchase purchase, Product product, int quantity, String location,
                                      String description, PurchaseItem item, User user) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(StockMovement.MovementType.OUT);
        movement.setQuantity(quantity);
        movement.setReferenceType(StockMovement.ReferenceType.RTV);
        movement.setReferenceId(purchase.getId());
        movement.setPurchaseItemId(item.getId());
        movement.setReasonCode("RETURN_TO_VENDOR");
        movement.setLocation(location);
        movement.setDescription(description + " - purchase " + purchase.getPurchaseNumber());
        movement.setValuationAmount(item.getBaseUnitCost()
                .multiply(BigDecimal.valueOf(quantity)).negate()
                .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
        movement.setCreatedBy(user);
        movement.setMovementDate(shopTimeService.now());
        stockMovementRepository.save(movement);
    }

    // ------------------------------------------------------------------
    // Reversal / update / delete
    // ------------------------------------------------------------------

    /**
     * Reverses the stock and weighted-average cost contribution of one purchase
     * line. Exactly the received-minus-returned quantity is reversed, taken
     * back from the locations the original receipt put it in (warehouse and
     * quarantine first, shop last). Removed units are valued at their own
     * landed base cost; the remaining stock keeps the weighted average over
     * ALL owned units. Refuses to reverse stock that has already been sold.
     */
    private void reversePurchaseItem(Purchase purchase, PurchaseItem item, Long userId, String operation) {
        Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + item.getProduct().getId()));

        int quantity = item.getReceivedQuantity() - item.getReturnedQuantity();
        if (quantity <= 0) {
            return;
        }

        BigDecimal oldCostPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        User user = userRepository.findById(userId).orElse(null);

        // P1/O8: post the valued reversal BEFORE removing stock; sole writer of cost.
        // Removed units are valued at their own landed base cost over ALL owned units,
        // with the C8 negative-value guard raising a reconciliation alert.
        InventoryLedger posting = ledgerPostingService.postInventory(
                product, InventoryLedger.EventType.ADJUSTMENT, "PURCHASE_REVERSAL", purchase.getId(),
                item.getId() + ":" + quantity, -quantity, item.getBaseUnitCost(),
                item.getBaseUnitCost().multiply(BigDecimal.valueOf(quantity)).negate(),
                null, "Purchase " + operation, shopTimeService.now(), userId,
                message -> recordCostReconciliationAlert(userId, product, purchase, message));
        BigDecimal newCostPrice = posting.getUnitCostAfter();

        // Where did this line's receipt put the goods? Reverse in place,
        // following the same receipt-location precedence used for RTVs.
        Map<String, Integer> removedByLocation = new LinkedHashMap<>();
        removeUnitsFromReceiptLocations(purchase, item, product, quantity, false, "Purchase " + operation, userId, removedByLocation);
        int remaining = quantity;
        for (Map.Entry<String, Integer> removedEntry : removedByLocation.entrySet()) {
            int take = removedEntry.getValue();
            if (take <= 0) {
                continue;
            }
            recordReversalMovement(purchase, item, product, take, removedEntry.getKey(), user);
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(
                    "Cannot " + operation + " purchase because " + product.getName()
                            + " is short " + remaining + " unit(s) of the " + quantity
                            + " unit(s) that must be reversed (already sold)");
        }

        // Stock removed above; cost already revalued by the ledger posting.
        productRepository.save(product);

        if (oldCostPrice.compareTo(newCostPrice) != 0) {
            ProductPriceHistory costHistory = new ProductPriceHistory();
            costHistory.setProduct(product);
            costHistory.setOldPrice(oldCostPrice);
            costHistory.setNewPrice(newCostPrice);
            costHistory.setPriceType(ProductPriceHistory.PriceType.COST);
            costHistory.setSource(ProductPriceHistory.Source.PURCHASE_REVERSAL);
            costHistory.setPurchaseId(purchase.getId());
            costHistory.setSupplierId(purchase.getSupplier() != null ? purchase.getSupplier().getId() : null);
            costHistory.setReason("Purchase " + operation);
            costHistory.setChangedBy(user);
            costHistory.setPurchaseSupplierName(purchase.getSupplier() != null
                    ? purchase.getSupplier().getName() : null);
            costHistory.setPurchaseQuantity(-quantity);
            costHistory.setPurchaseUnitPrice(item.getBaseUnitCost());
            costHistory.setStockQtyAtChange(locationService.totalOwnedUnits(product));
            costHistory.setInventoryValueDelta(item.getBaseUnitCost()
                    .multiply(BigDecimal.valueOf(quantity)).negate());
            priceHistoryRepository.save(costHistory);
        }
    }

    private void recordCostReconciliationAlert(Long userId, Product product, Purchase purchase, String message) {
        String description = "Product " + product.getId() + ", purchase " + purchase.getPurchaseNumber() + ": " + message;
        log.warn("Cost reconciliation alert: {}", description);
        auditLogService.logAction(userId, "COST_RECONCILIATION_ALERT", description,
                "Purchase", purchase.getId(), null, null);
    }

    private void recordReversalMovement(Purchase purchase, PurchaseItem item, Product product,
                                        int quantity, String locationCode, User user) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(StockMovement.MovementType.OUT);
        movement.setQuantity(quantity);
        movement.setReferenceType(StockMovement.ReferenceType.PURCHASE);
        movement.setReferenceId(purchase.getId());
        movement.setPurchaseItemId(item.getId());
        movement.setDescription("Stock reversed from purchase: " + purchase.getPurchaseNumber());
        movement.setLocation(locationCode);
        movement.setValuationAmount(item.getBaseUnitCost().multiply(BigDecimal.valueOf(quantity))
                .negate().setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
        movement.setCreatedBy(user);
        movement.setMovementDate(shopTimeService.now());
        stockMovementRepository.save(movement);
    }

    private String generatePurchaseNumber() {
        return sequenceService.nextPurchaseNumber();
    }

    public void deletePurchase(Long id, Long userId) {
        periodGuard.assertOpen(shopTimeService.now());
        Purchase purchase = purchaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));

        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + id);
        }

        // Money already paid to the supplier is evidence of a real invoice. Deleting
        // the row would drop the remaining payable and the payment history from every
        // report while the cash stays gone from the drawer, leaving the AP control
        // account in debit with nothing to offset it.
        BigDecimal paidOnPurchase = purchasePaymentService.paidAmount(purchase.getId());
        if (paidOnPurchase != null && paidOnPurchase.signum() > 0) {
            throw new BusinessException("This purchase already has " + paidOnPurchase.toPlainString()
                    + " recorded in supplier payments. Refund or remove those payments before deleting it,"
                    + " so the money paid stays traceable to an invoice.");
        }

        // An ORDERED PO with nothing received never touched stock: deleting it
        // must not write stock or cost rows. Partially received POs reverse
        // exactly the received quantity.
        boolean hasReceived = purchase.getItems().stream()
                .anyMatch(item -> item.getReceivedQuantity() > 0);
        if (hasReceived) {
            for (PurchaseItem item : purchase.getItems()) {
                reversePurchaseItem(purchase, item, userId, "delete");
            }
        }

        purchase.setDeletedAt(shopTimeService.now());
        purchase.setIsActive(false);
        purchaseRepository.save(purchase);

        auditLogService.logAction(userId, "PURCHASE_DELETE",
                "Purchase deleted: " + purchase.getPurchaseNumber(),
                "Purchase", purchase.getId(), purchase.toString(), null);
    }

    public PurchaseResponse updatePurchase(Long purchaseId, PurchaseCreateRequest request, Long userId) {
        periodGuard.assertOpen(LocalDate.parse(request.getPurchaseDate(), DateTimeFormatter.ISO_LOCAL_DATE));
        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        periodGuard.assertOpen(purchase.getPurchaseDate());

        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }
        assertNotPartiallyReceived(purchase, "edit");
        if (purchase.getItems().stream().anyMatch(item -> item.getReturnedQuantity() > 0)) {
            throw new BusinessException("Cannot edit a purchase that has returned goods. Create a new purchase or adjustment.");
        }

        Supplier supplier = null;
        if (request.getSupplierId() != null) {
            supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Supplier not found: " + request.getSupplierId()));
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Purchase must have at least one item");
        }

        boolean wasReceived = purchase.getReceivingStatus() == Purchase.ReceivingStatus.RECEIVED;
        for (PurchaseItem oldItem : purchase.getItems()) {
            if (wasReceived) {
                reversePurchaseItem(purchase, oldItem, userId, "update");
            }
        }

        purchase.setSupplier(supplier);
        purchase.setPurchaseDate(LocalDate.parse(request.getPurchaseDate(), DateTimeFormatter.ISO_LOCAL_DATE));
        purchase.setNotes(request.getNotes());
        purchase.setCurrencyCode(normalizeCurrency(request.getCurrencyCode()));
        purchase.setExchangeRate(normalizeExchangeRate(request.getExchangeRate()));
        purchase.setTaxRecoverable(request.getTaxRecoverable() != null && request.getTaxRecoverable());

        for (PurchaseItem item : purchase.getItems()) {
            purchaseItemRepository.delete(item);
        }
        purchase.getItems().clear();

        for (PurchaseCreateRequest.PurchaseItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + itemRequest.getProductId()));
            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitCost(itemRequest.getUnitCost());
            item.setTotalCost(itemRequest.getUnitCost().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
            item.setExchangeRate(purchase.getExchangeRate());
            purchase.getItems().add(item);
        }

        applyExpenses(purchase, request.getExpenses());
        applyTotals(purchase, request);

        Purchase savedPurchase = purchaseRepository.saveAndFlush(purchase);
        applyLandedCosts(savedPurchase);

        boolean receiveNow = request.getReceiveNow() == null || request.getReceiveNow();
        if (receiveNow) {
            receiveAll(savedPurchase, userId);
        } else {
            savedPurchase.setReceivingStatus(Purchase.ReceivingStatus.ORDERED);
            purchaseRepository.save(savedPurchase);
        }
        purchasePaymentService.refreshPaymentStatus(savedPurchase);

        auditLogService.logAction(userId, "PURCHASE_UPDATE",
                "Purchase updated: " + savedPurchase.getPurchaseNumber(),
                "Purchase", savedPurchase.getId(), null, savedPurchase.toString());
        return mapToResponse(savedPurchase);
    }

    private void assertNotPartiallyReceived(Purchase purchase, String operation) {
        boolean anyReceived = purchase.getItems().stream()
                .anyMatch(item -> item.getReceivedQuantity() > 0
                        && item.getReceivedQuantity() < item.getQuantity());
        if (anyReceived || purchase.getReceivingStatus() == Purchase.ReceivingStatus.PARTIAL) {
            throw new BusinessException("Cannot " + operation
                    + " a partially received purchase. Complete receiving or return the goods to the vendor.");
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private PurchaseResponse mapToResponse(Purchase purchase) {
        PurchaseResponse response = new PurchaseResponse();
        response.setId(purchase.getId());
        response.setPurchaseNumber(purchase.getPurchaseNumber());
        response.setSupplierId(purchase.getSupplier() != null ? purchase.getSupplier().getId() : null);
        response.setSupplierName(purchase.getSupplier() != null ? purchase.getSupplier().getName() : "Walk-in");
        response.setPurchaseDate(purchase.getPurchaseDate());
        response.setSubtotal(purchase.getSubtotal());
        response.setTaxAmount(purchase.getTaxAmount());
        response.setTotalAmount(purchase.getTotalAmount());
        response.setDiscountAmount(purchase.getDiscountAmount());
        response.setTaxRecoverable(purchase.isTaxRecoverable());
        response.setVarianceAmount(purchase.getVarianceAmount());
        response.setReturnedAmount(purchase.getReturnedAmount());
        response.setPayableAmount(purchase.getPayableAmount());
        // O3: status / paid / outstanding are always derived from recorded
        // payments and returned goods, never from a hand-set value.
        BigDecimal paidAmount = purchasePaymentService.paidAmount(purchase.getId());
        BigDecimal payable = purchase.getPayableAmount();
        response.setPaidAmount(paidAmount);
        response.setOutstandingAmount(payable.subtract(paidAmount).max(BigDecimal.ZERO));
        response.setPaymentStatus(PurchasePaymentService.deriveStatus(payable, paidAmount).name());
        response.setReceivingStatus(purchase.getReceivingStatus().name());
        response.setCurrencyCode(purchase.getCurrencyCode());
        response.setExchangeRate(purchase.getExchangeRate());
        response.setLandedCostTotal(purchase.getLandedCostTotal());
        response.setReceivedAt(purchase.getReceivedAt());
        response.setReceivedBy(purchase.getReceivedBy());
        response.setNotes(purchase.getNotes());
        response.setCreatedBy(purchase.getCreatedBy());
        response.setIsActive(purchase.getIsActive());
        response.setCreatedAt(purchase.getCreatedAt());
        response.setUpdatedAt(purchase.getUpdatedAt());

        if (purchase.getItems() != null) {
            response.setItems(purchase.getItems().stream()
                    .map(this::mapItemToResponse)
                    .collect(Collectors.toList()));
        }
        if (purchase.getExpenses() != null) {
            response.setExpenses(purchase.getExpenses().stream().map(expense -> {
                PurchaseResponse.Expense dto = new PurchaseResponse.Expense();
                dto.setId(expense.getId());
                dto.setDescription(expense.getDescription());
                dto.setType(expense.getType().name());
                dto.setAmount(expense.getAmount());
                dto.setAllocationMethod(expense.getAllocationMethod().name());
                return dto;
            }).collect(Collectors.toList()));
        }
        return response;
    }

    private PurchaseItemResponse mapItemToResponse(PurchaseItem item) {
        PurchaseItemResponse response = new PurchaseItemResponse();
        response.setId(item.getId());
        response.setProductId(item.getProduct().getId());
        response.setProductName(item.getProduct().getName());
        response.setQuantity(item.getQuantity());
        response.setUnitCost(item.getUnitCost());
        response.setTotalCost(item.getTotalCost());
        response.setReceivedQuantity(item.getReceivedQuantity());
        response.setReturnedQuantity(item.getReturnedQuantity());
        response.setLandedUnitCost(item.getLandedUnitCost());
        response.setBaseUnitCost(item.getBaseUnitCost());
        response.setExchangeRate(item.getExchangeRate());
        response.setBinLocation(item.getProduct().getBinLocation());
        return response;
    }

    public SupplierStatsResponse getSupplierStats(Long supplierId) {
        LocalDateTime now = shopTimeService.now();
        LocalDateTime startOfWeek = now.with(java.time.DayOfWeek.MONDAY).toLocalDate().atStartOfDay();
        LocalDateTime startOfMonth = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        LocalDateTime startOfYear = now.withDayOfYear(1).toLocalDate().atStartOfDay();
        LocalDateTime startOfTime = LocalDateTime.of(2000, 1, 1, 0, 0);

        SupplierStatsResponse stats = new SupplierStatsResponse();
        stats.setTotalSpentAllTime(safeSum(purchaseRepository.sumTotalAmountBySupplierIdAndDateAfter(supplierId, startOfTime)));
        stats.setTotalSpentThisWeek(safeSum(purchaseRepository.sumTotalAmountBySupplierIdAndDateAfter(supplierId, startOfWeek)));
        stats.setTotalSpentThisMonth(safeSum(purchaseRepository.sumTotalAmountBySupplierIdAndDateAfter(supplierId, startOfMonth)));
        stats.setTotalSpentThisYear(safeSum(purchaseRepository.sumTotalAmountBySupplierIdAndDateAfter(supplierId, startOfYear)));
        stats.setTotalInvoices(purchaseRepository.countInvoicesBySupplierId(supplierId) != null
                ? purchaseRepository.countInvoicesBySupplierId(supplierId) : 0L);
        return stats;
    }

    private BigDecimal safeSum(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public List<SupplierTopProductResponse> getSupplierTopProducts(Long supplierId) {
        org.springframework.data.domain.Pageable topTen = org.springframework.data.domain.PageRequest.of(0, 10);
        List<Object[]> results = purchaseRepository.findTopProductsBySupplierId(supplierId, topTen);
        return results.stream().map(row -> {
            SupplierTopProductResponse response = new SupplierTopProductResponse();
            response.setProductId(((Number) row[0]).longValue());
            response.setProductName((String) row[1]);
            response.setTotalQuantity(((Number) row[2]).longValue());
            response.setTotalAmount((BigDecimal) row[3]);
            return response;
        }).collect(Collectors.toList());
    }
}
