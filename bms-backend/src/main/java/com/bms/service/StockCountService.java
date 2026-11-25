package com.bms.service;

import com.bms.dto.request.CountSheetCreateRequest;
import com.bms.dto.request.CountSubmitRequest;
import com.bms.dto.request.SyncCountSheetRequest;
import com.bms.dto.response.CountSheetResponse;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.exception.UnprocessableEntityException;
import com.bms.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ABC cycle counting, blind count submission, variance approval and
 * reason-coded stock adjustments. The scheduled methods keep classification
 * and count sheets current without any manual setup.
 */
@Service
@Transactional
public class StockCountService {

    public static final List<String> REASON_CODES = InventoryLossService.ADJUSTMENT_REASONS;

    private static final Logger log = LoggerFactory.getLogger(StockCountService.class);

    private static final BigDecimal AUTO_APPROVE_DEFAULT = BigDecimal.valueOf(2);
    private static final BigDecimal AUTO_APPROVE_LINE_VALUE_DEFAULT = new BigDecimal("20.00");
    private static final BigDecimal AUTO_APPROVE_SHEET_VALUE_DEFAULT = new BigDecimal("100.00");
    private static final int AUTO_APPROVE_MAX_LINES_DEFAULT = 50;
    private static final int SYNC_MAX_BACKDATE_DAYS_DEFAULT = 7;
    private static final int MAX_SYNC_ITEMS = 1000;
    private static final int MAX_COUNTED_QUANTITY_DEFAULT = 1_000_000;

    private final CountSheetRepository countSheetRepository;
    private final CountSheetItemRepository countSheetItemRepository;
    private final ProductRepository productRepository;
    private final SaleItemRepository saleItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AuditLogService auditLogService;
    private final LedgerPostingService ledgerPostingService;
    private final ShopTimeService shopTimeService;

    @org.springframework.beans.factory.annotation.Autowired
    private OrderRepository orderRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private PeriodGuard periodGuard;

    @org.springframework.beans.factory.annotation.Autowired
    private LocationService locationService;

    public StockCountService(CountSheetRepository countSheetRepository,
                             CountSheetItemRepository countSheetItemRepository,
                             ProductRepository productRepository,
                             SaleItemRepository saleItemRepository,
                             StockMovementRepository stockMovementRepository,
                             UserRepository userRepository,
                             SystemSettingRepository systemSettingRepository,
                             AuditLogService auditLogService,
                             LedgerPostingService ledgerPostingService,
                             ShopTimeService shopTimeService) {
        this.countSheetRepository = countSheetRepository;
        this.countSheetItemRepository = countSheetItemRepository;
        this.productRepository = productRepository;
        this.saleItemRepository = saleItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.userRepository = userRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.auditLogService = auditLogService;
        this.ledgerPostingService = ledgerPostingService;
        this.shopTimeService = shopTimeService;
    }

    // ------------------------------------------------------------------
    // ABC classification
    // ------------------------------------------------------------------

    /** Reclassifies products by revenue contribution over the last 90 days. */
    public int refreshAbcClasses() {
        LocalDateTime from = shopTimeService.now().minusDays(90);
        Map<Long, BigDecimal> revenue = new HashMap<>();
        for (Object[] row : saleItemRepository.findRevenueByProductSince(from)) {
            Long productId = ((Number) row[0]).longValue();
            BigDecimal amount = row[1] instanceof BigDecimal
                    ? (BigDecimal) row[1] : BigDecimal.valueOf(((Number) row[1]).doubleValue());
            revenue.put(productId, amount);
        }

        List<Product> products = productRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByNameAsc();
        products.sort((a, b) -> revenue.getOrDefault(b.getId(), BigDecimal.ZERO)
                .compareTo(revenue.getOrDefault(a.getId(), BigDecimal.ZERO)));
        BigDecimal total = revenue.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cumulative = BigDecimal.ZERO;
        int changed = 0;
        for (Product product : products) {
            BigDecimal amount = revenue.getOrDefault(product.getId(), BigDecimal.ZERO);
            cumulative = cumulative.add(amount);
            double share = total.compareTo(BigDecimal.ZERO) > 0
                    ? cumulative.divide(total, 6, RoundingMode.HALF_UP).doubleValue()
                    : 1.0;
            String abc = share <= 0.80 ? "A" : share <= 0.95 ? "B" : "C";
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                abc = "C";
            }
            if (!abc.equals(product.getAbcClass())) {
                product.setAbcClass(abc);
                productRepository.save(product);
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Scheduled generation
    // ------------------------------------------------------------------

    @Scheduled(cron = "0 30 2 * * MON")
    public void scheduledAbcRefresh() {
        refreshAbcClasses();
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledCountSheetGeneration() {
        generateDueSheets();
    }

    /** Creates one sheet per ABC class for every product that is due. */
    public List<CountSheet> generateDueSheets() {
        LocalDate today = shopTimeService.today();
        Set<Long> alreadyOpen = new HashSet<>(
                countSheetRepository.findProductIdsOnOpenSheets(List.of(
                        CountSheet.CountStatus.DRAFT,
                        CountSheet.CountStatus.IN_PROGRESS,
                        CountSheet.CountStatus.PENDING_REVIEW)));

        List<CountSheet> created = new ArrayList<>();
        created.addAll(generateForClass("A", CountSheet.CountType.ABC_A, 14, today, alreadyOpen));
        created.addAll(generateForClass("B", CountSheet.CountType.ABC_B, 60, today, alreadyOpen));
        created.addAll(generateForClass("C", CountSheet.CountType.ABC_C, 365, today, alreadyOpen));
        return created;
    }

    private List<CountSheet> generateForClass(String abcClass, CountSheet.CountType type,
                                              int intervalDays, LocalDate today, Set<Long> alreadyOpen) {
        List<Product> due = productRepository.findDueForCount(abcClass, today.minusDays(intervalDays))
                .stream()
                .filter(p -> !alreadyOpen.contains(p.getId()))
                .limit(300)
                .collect(Collectors.toList());
        if (due.isEmpty()) {
            return List.of();
        }
        CountSheet sheet = buildSheet(type, due, null, "Scheduled " + abcClass + " cycle count");
        sheet = countSheetRepository.save(sheet);
        due.forEach(p -> alreadyOpen.add(p.getId()));
        return List.of(sheet);
    }

    // ------------------------------------------------------------------
    // Sheet lifecycle
    // ------------------------------------------------------------------

    public CountSheetResponse createManual(CountSheetCreateRequest request, Long userId) {
        List<Product> products;
        if (request.getProductIds() != null && !request.getProductIds().isEmpty()) {
            products = productRepository.findAllById(request.getProductIds()).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsActive()) && p.getDeletedAt() == null)
                    .collect(Collectors.toList());
        } else {
            products = productRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByNameAsc();
        }
        if (products.isEmpty()) {
            throw new BusinessException("No products available for counting");
        }
        CountSheet.CountType type = parseType(request.getCountType());
        CountSheet sheet = buildSheet(type, products, userId, request.getNotes());
        return toResponse(countSheetRepository.save(sheet), false);
    }

    private CountSheet buildSheet(CountSheet.CountType type, List<Product> products,
                                  Long userId, String notes) {
        CountSheet sheet = new CountSheet();
        sheet.setSheetNumber(nextSheetNumber());
        sheet.setCountType(type);
        sheet.setStatus(CountSheet.CountStatus.DRAFT);
        sheet.setScheduledDate(shopTimeService.today());
        sheet.setCreatedBy(userId);
        sheet.setNotes(notes);
        for (Product product : products) {
            CountSheetItem item = new CountSheetItem();
            item.setCountSheet(sheet);
            item.setProduct(product);
            item.setSystemQuantity(product.getStockQuantity() != null ? product.getStockQuantity() : 0);
            sheet.getItems().add(item);
        }
        return sheet;
    }

    public CountSheetResponse start(Long id) {
        CountSheet sheet = getSheetForUpdate(id);
        if (sheet.getStatus() == CountSheet.CountStatus.DRAFT) {
            transitionSheet(sheet, CountSheet.CountStatus.DRAFT, CountSheet.CountStatus.IN_PROGRESS,
                    "Count sheet is no longer in draft");
            sheet.setStatus(CountSheet.CountStatus.IN_PROGRESS);
            sheet.setStartedAt(shopTimeService.now());
            countSheetRepository.save(sheet);
        }
        return toResponse(sheet, false);
    }

    @Transactional(readOnly = true)
    public Page<CountSheetResponse> list(CountSheet.CountStatus status, Pageable pageable) {
        Page<CountSheet> page = status != null
                ? countSheetRepository.findByStatusInOrderByCreatedAtDescIdDesc(List.of(status), pageable)
                : countSheetRepository.findAllByOrderByCreatedAtDescIdDesc(pageable);
        return page.map(sheet -> toResponse(sheet, false));
    }

    @Transactional(readOnly = true)
    public CountSheetResponse get(Long id, boolean blind) {
        return toResponse(getSheet(id), blind);
    }

    /** Blind count: expected quantities are hidden until submission. */
    @Transactional(readOnly = true)
    public CountSheetResponse getBlind(Long id) {
        return get(id, true);
    }

    public CountSheetResponse submit(Long id, CountSubmitRequest request, Long userId) {
        CountSheet sheet = getSheetForUpdate(id);
        if (sheet.getStatus() != CountSheet.CountStatus.IN_PROGRESS) {
            throw new BusinessException("Only an in-progress count sheet can be submitted");
        }
        List<CountSheetItem> items = countSheetItemRepository.findWithProductBySheetId(id);
        if (items.isEmpty()) {
            throw new BusinessException("Count sheet has no items");
        }
        Map<Long, CountSubmitRequest.Entry> entries = new HashMap<>();
        for (CountSubmitRequest.Entry entry : request.getItems()) {
            entries.put(entry.getItemId(), entry);
        }
        if (entries.size() != request.getItems().size()) {
            throw new BusinessException("Duplicate count entries");
        }
        snapshotSystemQuantitiesAtCountTime(items);
        for (CountSheetItem item : items) {
            CountSubmitRequest.Entry entry = entries.get(item.getId());
            if (entry == null || entry.getCountedQuantity() == null) {
                throw new BusinessException("Every item must be counted before submitting");
            }
            applyCountEntry(item, entry.getCountedQuantity(), entry.getReasonCode(), entry.getNote());
        }

        transitionSheet(sheet, CountSheet.CountStatus.IN_PROGRESS, CountSheet.CountStatus.PENDING_REVIEW,
                "Count sheet is no longer in progress");
        sheet.setSubmittedAt(shopTimeService.now());
        sheet.setSubmittedBy(userId);
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            sheet.setNotes(request.getNotes());
        }
        recalculateVarianceValue(sheet, items);

        if (isWithinAutoApproveThreshold(sheet, items) && liveVariancesHaveReasonCodes(items)) {
            applySheet(sheet, userId);
            auditLogService.logAction(userId, "STOCK_COUNT_AUTO_APPROVE",
                    "Count sheet auto-approved using unit limit "
                            + decimalSetting("stockCountAutoApproveThreshold", AUTO_APPROVE_DEFAULT)
                            + ", line value limit "
                            + decimalSetting("stockCountAutoApproveLineValue", AUTO_APPROVE_LINE_VALUE_DEFAULT)
                            + ", sheet value limit "
                            + decimalSetting("stockCountAutoApproveSheetValue", AUTO_APPROVE_SHEET_VALUE_DEFAULT)
                            + ", max non-zero lines "
                            + integerSetting("stockCountAutoApproveMaxLines", AUTO_APPROVE_MAX_LINES_DEFAULT)
                            + ", absolute variance total " + sheet.getVarianceValue(),
                    "CountSheet", sheet.getId(), null, String.valueOf(sheet.getVarianceValue()));
        } else {
            countSheetRepository.save(sheet);
        }
        return toResponse(sheet, false);
    }

    /**
     * Defect 1: the sheet snapshot is taken when the sheet is built (often at
     * 03:00). Anything sold between then and the physical count must remain a
     * sale, not a shrinkage. Re-read each product under a row lock at submission
     * and use that live quantity as the count-time snapshot. Sorted ids keep the
     * lock order stable against other stock-adjustment paths.
     */
    private void snapshotSystemQuantitiesAtCountTime(List<CountSheetItem> items) {
        java.util.Map<Long, Product> locked = new java.util.TreeMap<>();
        for (CountSheetItem item : items) {
            Product product = item.getProduct();
            if (product == null || product.getId() == null || locked.containsKey(product.getId())) {
                continue;
            }
            Product live = productRepository.findByIdForUpdate(product.getId())
                    .orElseThrow(() -> new BusinessException("Product not found: " + product.getId()));
            locked.put(product.getId(), live);
        }
        for (CountSheetItem item : items) {
            Product product = item.getProduct();
            if (product == null || product.getId() == null) {
                continue;
            }
            Product live = locked.get(product.getId());
            if (live != null) {
                item.setSystemQuantity(live.getStockQuantity() != null ? live.getStockQuantity() : 0);
            }
        }
    }

    public CountSheetResponse approve(Long id, Long userId) {
        CountSheet sheet = getSheetForUpdate(id);
        if (sheet.getStatus() != CountSheet.CountStatus.PENDING_REVIEW) {
            throw new BusinessException("Only a pending-review sheet can be approved");
        }
        List<CountSheetItem> items = countSheetItemRepository.findWithProductBySheetId(id);
        if (items.stream().anyMatch(item -> item.getCountedQuantity() == null)) {
            throw new BusinessException("Count sheet still has uncounted items");
        }
        User approver = userRepository.findById(userId).orElse(null);
        boolean admin = approver != null && approver.getRole() != null
                && approver.getRole().getName() == Role.RoleName.ROLE_ADMIN;
        if (!admin && Objects.equals(sheet.getSubmittedBy(), userId)) {
            throw new BusinessException("Approver must differ from the counter");
        }
        applySheet(sheet, userId);
        return toResponse(sheet, false);
    }

    public CountSheetResponse reject(Long id, Long userId, String reason) {
        CountSheet sheet = getSheetForUpdate(id);
        if (sheet.getStatus() != CountSheet.CountStatus.PENDING_REVIEW
                && sheet.getStatus() != CountSheet.CountStatus.IN_PROGRESS) {
            throw new BusinessException("This count sheet can no longer be rejected");
        }
        transitionSheet(sheet, sheet.getStatus(), CountSheet.CountStatus.CANCELLED,
                "Count sheet status changed before it could be rejected");
        if (reason != null && !reason.isBlank()) {
            sheet.setNotes((sheet.getNotes() != null ? sheet.getNotes() + "\n" : "")
                    + "Rejected: " + reason);
        }
        countSheetRepository.save(sheet);
        auditLogService.logAction(userId, "STOCK_COUNT_REJECT",
                "Count sheet rejected: " + sheet.getSheetNumber(), "CountSheet", sheet.getId(),
                null, reason);
        return toResponse(sheet, false);
    }

    private void applyCountEntry(CountSheetItem item, int countedQuantity,
                                 String reasonCode, String note) {
        if (countedQuantity < 0) {
            throw new BusinessException("Counted quantity cannot be negative");
        }
        int variance = countedQuantity - item.getSystemQuantity();
        if (variance != 0 && (reasonCode == null || reasonCode.isBlank())) {
            throw new BusinessException("Reason code is required for item " + item.getProduct().getName());
        }
        if (reasonCode != null && !reasonCode.isBlank()
                && !REASON_CODES.contains(reasonCode.toUpperCase())) {
            throw new BusinessException("Invalid reason code: " + reasonCode);
        }
        BigDecimal cost = item.getProduct().getCostPrice() != null
                ? item.getProduct().getCostPrice() : BigDecimal.ZERO;
        item.setCountedQuantity(countedQuantity);
        item.setVariance(variance);
        item.setReasonCode(reasonCode != null ? reasonCode.toUpperCase() : null);
        item.setNote(note);
        item.setCountedAt(shopTimeService.now());
        item.setValuationAmount(cost.multiply(BigDecimal.valueOf(variance))
                .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
    }

    private void recalculateVarianceValue(CountSheet sheet, List<CountSheetItem> items) {
        BigDecimal total = items.stream()
                .map(CountSheetItem::getValuationAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sheet.setVarianceValue(total);
    }

    private boolean isWithinAutoApproveThreshold(CountSheet sheet, List<CountSheetItem> items) {
        BigDecimal unitLimit = decimalSetting("stockCountAutoApproveThreshold", AUTO_APPROVE_DEFAULT);
        BigDecimal lineValueLimit = decimalSetting("stockCountAutoApproveLineValue", AUTO_APPROVE_LINE_VALUE_DEFAULT);
        BigDecimal sheetValueLimit = decimalSetting("stockCountAutoApproveSheetValue", AUTO_APPROVE_SHEET_VALUE_DEFAULT);
        int maxLines = integerSetting("stockCountAutoApproveMaxLines", AUTO_APPROVE_MAX_LINES_DEFAULT);
        BigDecimal sheetAbs = BigDecimal.ZERO;
        int nonZeroLines = 0;
        for (CountSheetItem item : items) {
            if (item.getCountedQuantity() == null) {
                return false;
            }
            // Variance is measured against the count-time snapshot, not live stock
            // which may have changed since the count was submitted.
            int variance = item.getCountedQuantity() - item.getSystemQuantity();
            if (variance == 0) continue;
            nonZeroLines++;
            BigDecimal value = unitCostAtCount(item, item.getProduct())
                    .multiply(BigDecimal.valueOf(Math.abs((long) variance)));
            if (BigDecimal.valueOf(Math.abs((long) variance)).compareTo(unitLimit) > 0
                    || value.compareTo(lineValueLimit) > 0) {
                return false;
            }
            sheetAbs = sheetAbs.add(value);
        }
        return nonZeroLines <= maxLines && sheetAbs.compareTo(sheetValueLimit) <= 0;
    }

    /**
     * S1: the same rule applySheet enforces, evaluated without throwing so a
     * submission that needs a reason lands in PENDING_REVIEW with counts saved
     * instead of failing outright.
     */
    private boolean liveVariancesHaveReasonCodes(List<CountSheetItem> items) {
        for (CountSheetItem item : items) {
            if (item.getCountedQuantity() == null) {
                continue;
            }
            // Compare against the count-time snapshot, not live stock which may
            // have changed since submission.
            if (needsReasonCode(item.getCountedQuantity(), item.getSystemQuantity(), item.getReasonCode())) {
                return false;
            }
        }
        return true;
    }

    private static boolean needsReasonCode(int countedQuantity, int liveQuantity, String reasonCode) {
        return countedQuantity != liveQuantity && (reasonCode == null || reasonCode.isBlank());
    }

    private BigDecimal decimalSetting(String key, BigDecimal defaultValue) {
        String raw = settingValue(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            logBadSetting(key, raw);
            return defaultValue;
        }
    }

    private int integerSetting(String key, int defaultValue) {
        String raw = settingValue(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            logBadSetting(key, raw);
            return defaultValue;
        }
    }

    private String settingValue(String key) {
        return systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
    }

    /** S2: a hand-edited row must never take a count submission down with it. */
    private void logBadSetting(String key, String raw) {
        log.warn("Setting {} holds \"{}\" which is not a number; using the default", key, raw);
    }

    private void applySheet(CountSheet sheet, Long userId) {
        sheet = getSheetForUpdate(sheet.getId());
        periodGuard.assertOpen(sheet.getSubmittedAt() != null ? sheet.getSubmittedAt() : shopTimeService.now());
        if (sheet.getStatus() != CountSheet.CountStatus.PENDING_REVIEW) {
            throw new BusinessException("Only a pending-review sheet can be applied");
        }
        transitionSheet(sheet, CountSheet.CountStatus.PENDING_REVIEW, CountSheet.CountStatus.APPROVED,
                "Count sheet is no longer pending review");
        List<CountSheetItem> items = countSheetItemRepository.findWithProductBySheetId(sheet.getId()).stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .collect(Collectors.toList());
        User user = userRepository.findById(userId).orElse(null);
        LocalDate today = shopTimeService.today();
        Map<Long, Product> products = new HashMap<>();
        for (CountSheetItem item : items) {
            Long productId = item.getProduct().getId();
            products.computeIfAbsent(productId, id -> productRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id)));
        }
        List<String> missingReasons = new ArrayList<>();
        for (CountSheetItem item : items) {
            if (item.getCountedQuantity() == null) {
                continue;
            }
            Product product = products.get(item.getProduct().getId());
            int live = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            // Variance is measured against the snapshot taken at count time
            // (item.getSystemQuantity()), not against live stock which may have
            // changed since the count was submitted. Applying live + variance
            // keeps sold units from being resurrected by the count.
            int variance = item.getCountedQuantity() - item.getSystemQuantity();
            BigDecimal unitCost = unitCostAtCount(item, product);
            item.setLiveQuantityAtApply(live);
            item.setVarianceAtApply(variance);
            item.setVariance(variance);
            item.setValuationAmount(unitCost.multiply(BigDecimal.valueOf(variance))
                    .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP));
            if (needsReasonCode(item.getCountedQuantity(), item.getSystemQuantity(), item.getReasonCode())) {
                missingReasons.add(product.getName());
            }
        }
        if (!missingReasons.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Reason code is required for new variance on: " + String.join(", ", missingReasons));
        }
        recalculateVarianceValue(sheet, items);

        for (CountSheetItem item : items) {
            if (item.getCountedQuantity() == null) {
                continue;
            }
            int variance = item.getVarianceAtApply() != null ? item.getVarianceAtApply() : 0;
            Product product = products.get(item.getProduct().getId());
            int live = product.getStockQuantity() != null ? product.getStockQuantity() : 0;

            if (variance != 0) {
                StockMovement movement = new StockMovement();
                movement.setProduct(product);
                movement.setMovementType(variance > 0
                        ? StockMovement.MovementType.ADJUSTMENT_IN
                        : StockMovement.MovementType.ADJUSTMENT_OUT);
                movement.setQuantity(Math.abs(variance));
                movement.setReferenceType(StockMovement.ReferenceType.STOCK_COUNT);
                movement.setReferenceId(sheet.getId());
                movement.setReasonCode(item.getReasonCode());
                movement.setLocation("SHOP");
                movement.setValuationAmount(item.getValuationAmount());
                movement.setDescription("Stock count " + sheet.getSheetNumber()
                        + " variance " + variance + " for " + product.getName());
                movement.setCreatedBy(user);
                movement.setMovementDate(sheet.getSubmittedAt() != null ? sheet.getSubmittedAt() : shopTimeService.now());
                stockMovementRepository.save(movement);

                BigDecimal unitCost = unitCostAtCount(item, product);
                ledgerPostingService.postInventory(product, InventoryLedger.EventType.COUNT,
                        "STOCK_MOVEMENT", movement.getId(), "count", variance, unitCost,
                        item.getValuationAmount(), null,
                        movement.getDescription(), movement.getMovementDate(), userId);
                if (variance > 0) {
                    ledgerPostingService.postInventoryGain(movement);
                } else {
                    ledgerPostingService.postOperatingLoss(movement);
                }
            }

            int newQty = live + variance;
            if (newQty < 0) {
                throw new BusinessException("Count approval would drive stock negative for "
                        + product.getName() + " (live " + live + ", variance " + variance + ")");
            }
            product.setStockQuantity(newQty);
            // Keep the SHOP LocationStock bucket in sync with product.stockQuantity
            if (locationService != null) {
                locationService.setShopStock(product, newQty);
            }
            product.setLastCountDate(today);
            productRepository.save(product);
            if (product.getStockQuantity() < product.getReservedQuantity()) {
                flagPendingOrdersForCountShortage(product, sheet, userId);
            }
        }
        sheet.setApprovedAt(shopTimeService.now());
        sheet.setApprovedBy(userId);
        countSheetRepository.save(sheet);
        auditLogService.logAction(userId, "STOCK_COUNT_APPROVE",
                "Count sheet approved and stock adjusted: " + sheet.getSheetNumber(),
                "CountSheet", sheet.getId(), null, String.valueOf(sheet.getVarianceValue()));
    }

    private void flagPendingOrdersForCountShortage(Product product, CountSheet sheet, Long userId) {
        String note = "Stock shortage after count " + sheet.getSheetNumber();
        for (Order order : orderRepository.findPendingOrdersForProduct(
                product.getId(), Order.OrderStatus.PENDING)) {
            String existing = order.getNotes() != null ? order.getNotes().trim() : "";
            if (!existing.contains(note)) {
                order.setNotes(existing.isEmpty() ? note : existing + "\n" + note);
                orderRepository.save(order);
                auditLogService.logAction(userId, "ORDER_STOCK_SHORTAGE",
                        note + " for " + product.getName() + " on order " + order.getOrderNumber(),
                        "Order", order.getId(), existing, order.getNotes());
            }
        }
    }

    // ------------------------------------------------------------------
    // Idempotent offline sync
    // ------------------------------------------------------------------

    public CountSheetResponse sync(SyncCountSheetRequest request, Long userId) {
        Optional<CountSheet> existing = countSheetRepository.findBySyncKey(request.getSyncKey());
        if (existing.isPresent()) {
            return toResponse(existing.get(), false);
        }

        // O7: keep the client event time and the server receive time apart.
        // PeriodGuard assigns the accounting period from the guarded event
        // date (the client timestamp when present), never the receive time.
        LocalDateTime receivedAt = shopTimeService.now();
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Sync payload has no count items");
        }
        if (request.getItems().size() > MAX_SYNC_ITEMS) {
            throw new BusinessException("A sync payload cannot contain more than " + MAX_SYNC_ITEMS + " items");
        }
        LocalDateTime eventAt = request.getSubmittedAt() != null ? request.getSubmittedAt() : receivedAt;
        if (eventAt.isAfter(receivedAt.plusMinutes(5))) {
            throw new BusinessException("Submitted time cannot be more than 5 minutes in the future");
        }
        int maxBackdateDays = integerSetting("stockCount.sync.maxBackdateDays",
                SYNC_MAX_BACKDATE_DAYS_DEFAULT);
        int maxCountedQuantity = integerSetting("stockCount.maxCountedQuantity",
                MAX_COUNTED_QUANTITY_DEFAULT);
        LocalDateTime oldestAllowedEvent = receivedAt.minusDays(maxBackdateDays);
        boolean withinBackdateLimit = !eventAt.isBefore(oldestAllowedEvent);
        LocalDateTime guardedEventAt = eventAt.isBefore(oldestAllowedEvent) ? oldestAllowedEvent : eventAt;
        periodGuard.assertOpen(guardedEventAt);
        CountSheet sheet = new CountSheet();
        sheet.setSheetNumber(nextSheetNumber());
        sheet.setSyncKey(request.getSyncKey());
        sheet.setCountType(parseType(request.getCountType()));
        sheet.setStatus(CountSheet.CountStatus.IN_PROGRESS);
        sheet.setScheduledDate(shopTimeService.toShopDate(guardedEventAt));
        sheet.setStartedAt(eventAt);
        sheet.setSubmittedAt(guardedEventAt);
        sheet.setClientSubmittedAt(request.getSubmittedAt());
        sheet.setSyncReceivedAt(receivedAt);
        sheet.setSubmittedBy(userId);
        sheet.setCreatedBy(userId);
        sheet.setNotes(request.getNotes());

        List<CountSheetItem> items = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        boolean allProductsActive = true;
        for (SyncCountSheetRequest.Entry entry : request.getItems()) {
            if (entry.getProductId() == null || !seen.add(entry.getProductId())) {
                throw new BusinessException("Every sync item needs a unique product id");
            }
            Product product = productRepository.findById(entry.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + entry.getProductId()));
            if (entry.getCountedQuantity() == null || entry.getCountedQuantity() < 0
                    || entry.getCountedQuantity() > maxCountedQuantity) {
                throw new BusinessException("Counted quantity must be between 0 and "
                        + maxCountedQuantity);
            }
            if (!Boolean.TRUE.equals(product.getIsActive()) || product.getDeletedAt() != null) {
                allProductsActive = false;
            }
            CountSheetItem item = new CountSheetItem();
            item.setCountSheet(sheet);
            item.setProduct(product);
            // Snapshot the system quantity at count time (what the client saw when
            // counting), not live stock at sync time. Using live stock here would
            // re-introduce the "resurrected stock" bug: units sold between the
            // physical count and the sync would be counted as a variance and added
            // back on approval.
            int countTimeSystemQty = entry.getSystemQuantity() != null ? entry.getSystemQuantity() : 0;
            if (countTimeSystemQty < 0) {
                throw new BusinessException("System quantity cannot be negative for product "
                        + product.getName());
            }
            item.setClientSystemQuantity(entry.getSystemQuantity());
            item.setSystemQuantity(countTimeSystemQty);
            applyCountEntry(item, entry.getCountedQuantity(),
                    entry.getReasonCode(), entry.getNote());
            sheet.getItems().add(item);
            items.add(item);
        }
        recalculateVarianceValue(sheet, items);
        if (withinBackdateLimit && allProductsActive && isWithinAutoApproveThreshold(sheet, items)
                && liveVariancesHaveReasonCodes(items)) {
            sheet.setStatus(CountSheet.CountStatus.PENDING_REVIEW);
            CountSheet saved = countSheetRepository.saveAndFlush(sheet);
            applySheet(saved, userId);
            return toResponse(saved, false);
        }
        sheet.setStatus(CountSheet.CountStatus.PENDING_REVIEW);
        return toResponse(countSheetRepository.save(sheet), false);
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private CountSheet getSheet(Long id) {
        return countSheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Count sheet not found: " + id));
    }

    private CountSheet getSheetForUpdate(Long id) {
        return countSheetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Count sheet not found: " + id));
    }

    private void transitionSheet(CountSheet sheet, CountSheet.CountStatus expected,
                                CountSheet.CountStatus next, String errorMessage) {
        if (countSheetRepository.transitionStatus(sheet.getId(), expected, next) == 0) {
            throw new BusinessException(errorMessage);
        }
        sheet.setStatus(next);
    }

    private CountSheetResponse toResponse(CountSheet sheet, boolean blind) {
        CountSheetResponse response = new CountSheetResponse();
        response.setId(sheet.getId());
        response.setSheetNumber(sheet.getSheetNumber());
        response.setStatus(sheet.getStatus().name());
        response.setCountType(sheet.getCountType().name());
        response.setScheduledDate(sheet.getScheduledDate());
        response.setStartedAt(sheet.getStartedAt());
        response.setSubmittedAt(sheet.getSubmittedAt());
        response.setClientSubmittedAt(sheet.getClientSubmittedAt());
        response.setSyncReceivedAt(sheet.getSyncReceivedAt());
        response.setApprovedAt(sheet.getApprovedAt());
        response.setNotes(sheet.getNotes());
        response.setVarianceValue(sheet.getVarianceValue());
        response.setCreatedBy(sheet.getCreatedBy());
        response.setApprovedBy(sheet.getApprovedBy());

        List<CountSheetItem> items = sheet.getId() != null
                ? countSheetItemRepository.findWithProductBySheetId(sheet.getId())
                : sheet.getItems();
        response.setItemCount(items.size());
        for (CountSheetItem item : items) {
            CountSheetResponse.Item dto = new CountSheetResponse.Item();
            dto.setId(item.getId());
            dto.setProductId(item.getProduct().getId());
            dto.setProductName(item.getProduct().getName());
            dto.setSku(item.getProduct().getSku());
            dto.setBarcode(item.getProduct().getBarcode());
            dto.setBinLocation(item.getProduct().getBinLocation());
            dto.setUnit(item.getProduct().getUnit());
            dto.setUnitPrice(item.getProduct().getUnitPrice());
            // Blind counts never leak the expected quantity.
            dto.setSystemQuantity(blind ? null : item.getSystemQuantity());
            if (!blind) {
                if (sheet.getStatus() == CountSheet.CountStatus.PENDING_REVIEW) {
                    int live = item.getProduct().getStockQuantity() != null
                            ? item.getProduct().getStockQuantity() : 0;
                    dto.setLiveQuantityAtApply(live);
                    if (item.getCountedQuantity() != null) {
                        dto.setVarianceAtApply(item.getCountedQuantity() - live);
                    }
                } else {
                    dto.setLiveQuantityAtApply(item.getLiveQuantityAtApply());
                    dto.setVarianceAtApply(item.getVarianceAtApply());
                }
            }
            dto.setCountedQuantity(item.getCountedQuantity());
            dto.setVariance(blind ? null : item.getVariance());
            dto.setReasonCode(item.getReasonCode());
            dto.setValuationAmount(blind ? null : item.getValuationAmount());
            dto.setNote(item.getNote());
            response.getItems().add(dto);
        }
        return response;
    }

    private BigDecimal unitCostAtCount(CountSheetItem item, Product product) {
        if (item.getVariance() != null && item.getVariance() != 0
                && item.getValuationAmount() != null) {
            return item.getValuationAmount().divide(BigDecimal.valueOf(item.getVariance()),
                    CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);
        }
        return product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
    }

    private CountSheet.CountType parseType(String type) {
        if (type == null || type.isBlank()) {
            return CountSheet.CountType.MANUAL;
        }
        try {
            return CountSheet.CountType.valueOf(type.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BusinessException("Invalid count type: " + type);
        }
    }

    private String nextSheetNumber() {
        LocalDate today = shopTimeService.today();
        String prefix = "CS-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long next = countSheetRepository.countBySheetNumberStartingWith(prefix) + 1;
        String number = prefix + String.format("%04d", next);
        // Extremely unlikely, but retry if a concurrent request used the number.
        while (countSheetRepository.findBySheetNumber(number).isPresent()) {
            next++;
            number = prefix + String.format("%04d", next);
        }
        return number;
    }
}
