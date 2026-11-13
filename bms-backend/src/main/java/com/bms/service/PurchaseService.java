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
    private PasswordEncoder passwordEncoder;

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
        purchase.setPaymentStatus(resolvePaymentStatus(request.getPaymentStatus()));
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
            varianceExpense.setExpenseDate(LocalDate.now());
            varianceExpense.setCreatedBy(userId);
            expenseRepository.save(varianceExpense);
        }
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
        purchase.setReceivedAt(LocalDateTime.now());
        purchase.setReceivedBy(userId);
        purchaseRepository.save(purchase);
    }

    public PurchaseResponse receivePurchase(Long purchaseId, ReceivePurchaseRequest request, Long userId) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }
        if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.RECEIVED) {
            throw new BusinessException("Purchase " + purchase.getPurchaseNumber() + " is already fully received");
        }

        Map<Long, PurchaseItem> itemsById = purchase.getItems().stream()
                .collect(Collectors.toMap(PurchaseItem::getId, item -> item));
        boolean overShipment = false;
        for (ReceivePurchaseRequest.ReceiveItem entry : request.getItems()) {
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
            verifyManagerOverride(request.getManagerUsername(), request.getManagerPassword());
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
        purchase.setReceivedAt(LocalDateTime.now());
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
        int ownedBefore = locationService.totalOwnedUnits(product);
        BigDecimal oldCostPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        BigDecimal newWeightedCost = CostCalculationUtils.calculateWeightedAverageCost(
                oldCostPrice, ownedBefore, unitCost, received);
        BigDecimal sellableValuation = valuation.multiply(BigDecimal.valueOf(sellable))
                .divide(BigDecimal.valueOf(received), CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);

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
        if (quarantine > 0 && quarantineLocation != null) {
            locationService.addStock(product, quarantineLocation, quarantine);
            recordMovement(purchase, item, product, StockMovement.MovementType.IN, quarantine,
                    StockMovement.ReferenceType.RECEIVING, quarantineLocation.getCode(),
                    "Quarantined " + quarantine + " unit(s) of " + product.getName()
                            + " from purchase " + purchase.getPurchaseNumber(),
                    valuation.subtract(sellableValuation), user);
        }

        product.setCostPrice(newWeightedCost);
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
        movement.setMovementDate(LocalDateTime.now());
        stockMovementRepository.save(movement);
    }

    private void verifyManagerOverride(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(
                    "Received quantity exceeds the ordered quantity. Manager credentials are required.");
        }
        User manager = userRepository.findActiveByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new BusinessException("Invalid manager credentials"));
        Role.RoleName role = manager.getRole() != null ? manager.getRole().getName() : null;
        if (role != Role.RoleName.ROLE_ADMIN && role != Role.RoleName.ROLE_MANAGER) {
            throw new BusinessException("Only a manager or administrator can approve over-shipments");
        }
        if (!passwordEncoder.matches(password, manager.getPassword())) {
            throw new BusinessException("Invalid manager credentials");
        }
    }

    // ------------------------------------------------------------------
    // Return to vendor
    // ------------------------------------------------------------------

    public PurchaseResponse returnToVendor(Long purchaseId, ReturnToVendorRequest request, Long userId) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }

        Map<Long, PurchaseItem> itemsById = purchase.getItems().stream()
                .collect(Collectors.toMap(PurchaseItem::getId, item -> item));
        Location quarantine = locationService.getDefault(Location.LocationType.QUARANTINE);
        Location shop = locationService.getDefault(Location.LocationType.SHOP);
        User user = userRepository.findById(userId).orElse(null);

        BigDecimal totalCredit = BigDecimal.ZERO;
        for (ReturnToVendorRequest.ReturnItem entry : request.getItems()) {
            PurchaseItem item = itemsById.get(entry.getPurchaseItemId());
            if (item == null) {
                throw new BusinessException("Purchase item " + entry.getPurchaseItemId()
                        + " does not belong to this purchase");
            }
            int available = item.getReceivedQuantity() - item.getReturnedQuantity();
            if (entry.getQuantity() > available) {
                throw new BusinessException("Only " + available + " unit(s) of "
                        + item.getProduct().getName() + " can still be returned");
            }
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + item.getProduct().getId()));

            int ownedBefore = locationService.totalOwnedUnits(product);
            BigDecimal oldCostPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;

            // Draw only from quarantine stock that THIS purchase's receipt put
            // there; never from another PO's quarantined goods.
            int remaining = entry.getQuantity();
            int fromQuarantine = Math.min(remaining,
                    quarantineAvailableForItem(purchase, item, product, quarantine));
            if (fromQuarantine > 0) {
                locationService.removeStock(product, quarantine, fromQuarantine);
                remaining -= fromQuarantine;
                recordReturnMovement(purchase, product, fromQuarantine, quarantine.getCode(),
                        "RTV from quarantine", item, user);
            }
            if (remaining > 0) {
                int current = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                if (current < remaining) {
                    throw new BusinessException("Only " + current + " sellable unit(s) of "
                            + product.getName() + " are available to return");
                }
                locationService.removeShopStock(product, remaining);
                recordReturnMovement(purchase, product, remaining, shop.getCode(),
                        "RTV from shop floor", item, user);
            }

            // Revalue: the returned units leave at this line's own landed cost,
            // averaged over ALL owned units (shop + every location bucket).
            BigDecimal newCostPrice = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                    oldCostPrice, ownedBefore, item.getBaseUnitCost(), entry.getQuantity(),
                    message -> recordCostReconciliationAlert(userId, product, purchase, message));
            product.setCostPrice(newCostPrice);
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

    /**
     * Supplier credit for returned units (D1): the line's landed base cost.
     * Recoverable tax is credited pro-rata on top; non-recoverable tax (D2) is
     * already capitalised into the landed cost, so adding it again would double
     * count.
     */
    private BigDecimal rtvCreditAmount(Purchase purchase, PurchaseItem item, int quantity) {
        BigDecimal credit = item.getBaseUnitCost().multiply(BigDecimal.valueOf(quantity));
        if (purchase.isTaxRecoverable()) {
            BigDecimal tax = purchase.getTaxAmount();
            if (tax != null && tax.signum() > 0) {
                int ordered = purchase.getItems().stream()
                        .mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();
                if (ordered > 0) {
                    credit = credit.add(tax.multiply(BigDecimal.valueOf(quantity))
                            .divide(BigDecimal.valueOf(ordered), 2, RoundingMode.HALF_UP));
                }
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
        movement.setMovementDate(LocalDateTime.now());
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
        int ownedBefore = locationService.totalOwnedUnits(product);
        User user = userRepository.findById(userId).orElse(null);

        // Where did this line's receipt put the goods? Reverse in place.
        List<String> receiptLocations = stockMovementRepository
                .findByReferenceAndPurchaseItem(StockMovement.ReferenceType.RECEIVING,
                        purchase.getId(), item.getId())
                .stream()
                .filter(m -> m.getMovementType() == StockMovement.MovementType.IN)
                .map(StockMovement::getLocation)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(code -> "SHOP".equalsIgnoreCase(code) ? 1 : 0))
                .collect(Collectors.toList());

        int remaining = quantity;
        for (String code : receiptLocations) {
            if (remaining <= 0) {
                break;
            }
            Location location = locationService.findByCode(code).orElse(null);
            boolean isShop = location == null || location.getType() == Location.LocationType.SHOP;
            int available = isShop
                    ? (product.getStockQuantity() != null ? product.getStockQuantity() : 0)
                    : locationService.quantityIn(location, product);
            int take = Math.min(remaining, available);
            if (take <= 0) {
                continue;
            }
            if (isShop) {
                locationService.removeShopStock(product, take);
            } else {
                locationService.removeStock(product, location, take);
            }
            recordReversalMovement(purchase, item, product, take,
                    isShop ? "SHOP" : location.getCode(), user);
            remaining -= take;
        }
        // Legacy receipts (pre-receiving feature) have no movement trail;
        // goods may also have been transferred to the shop floor since.
        if (remaining > 0) {
            int available = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            int take = Math.min(remaining, available);
            if (take > 0) {
                locationService.removeShopStock(product, take);
                recordReversalMovement(purchase, item, product, take, "SHOP", user);
                remaining -= take;
            }
        }
        if (remaining > 0) {
            throw new BusinessException(
                    "Cannot " + operation + " purchase because " + product.getName()
                            + " is short " + remaining + " unit(s) of the " + quantity
                            + " unit(s) that must be reversed (already sold)");
        }

        BigDecimal newCostPrice = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                oldCostPrice, ownedBefore, item.getBaseUnitCost(), quantity,
                message -> recordCostReconciliationAlert(userId, product, purchase, message));
        product.setCostPrice(newCostPrice);
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
        movement.setMovementDate(LocalDateTime.now());
        stockMovementRepository.save(movement);
    }

    private String generatePurchaseNumber() {
        return sequenceService.nextPurchaseNumber();
    }

    private com.bms.entity.Purchase.PaymentStatus resolvePaymentStatus(String paymentStatus) {
        if (paymentStatus == null || paymentStatus.isBlank()) {
            return com.bms.entity.Purchase.PaymentStatus.PENDING;
        }
        try {
            return com.bms.entity.Purchase.PaymentStatus.valueOf(paymentStatus.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid payment status. Allowed values: PENDING, PARTIAL, PAID");
        }
    }

    public PurchaseResponse updatePaymentStatus(Long purchaseId, String paymentStatus, Long userId) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }

        String oldStatus = purchase.getPaymentStatus().name();
        try {
            purchase.setPaymentStatus(Purchase.PaymentStatus.valueOf(paymentStatus.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid payment status. Allowed values: PENDING, PARTIAL, PAID");
        }

        Purchase savedPurchase = purchaseRepository.save(purchase);
        auditLogService.logAction(userId, "PURCHASE_PAYMENT_STATUS_UPDATE",
                "Updated payment status for purchase: " + savedPurchase.getPurchaseNumber(),
                "Purchase", savedPurchase.getId(), oldStatus, savedPurchase.getPaymentStatus().name());
        return mapToResponse(savedPurchase);
    }

    public void deletePurchase(Long id, Long userId) {
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));

        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + id);
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

        purchase.setDeletedAt(LocalDateTime.now());
        purchase.setIsActive(false);
        purchaseRepository.save(purchase);

        auditLogService.logAction(userId, "PURCHASE_DELETE",
                "Purchase deleted: " + purchase.getPurchaseNumber(),
                "Purchase", purchase.getId(), purchase.toString(), null);
    }

    public PurchaseResponse updatePurchase(Long purchaseId, PurchaseCreateRequest request, Long userId) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));

        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }
        assertNotPartiallyReceived(purchase, "edit");

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
        response.setPaymentStatus(purchase.getPaymentStatus().name());
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
        LocalDateTime now = LocalDateTime.now();
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
