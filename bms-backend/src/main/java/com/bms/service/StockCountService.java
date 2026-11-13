package com.bms.service;

import com.bms.dto.request.CountSheetCreateRequest;
import com.bms.dto.request.CountSubmitRequest;
import com.bms.dto.request.SyncCountSheetRequest;
import com.bms.dto.response.CountSheetResponse;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
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

    public static final List<String> REASON_CODES = List.of(
            "SPOILAGE_EXPIRY", "THEFT_SHRINKAGE", "DAMAGED_IN_STORE",
            "INTERNAL_CONSUMPTION", "COUNT_CORRECTION",
            "DAMAGED", "THEFT", "EXPIRED", "MISCOUNT", "SUPPLIER_ERROR", "RECALL", "OTHER");

    private static final BigDecimal AUTO_APPROVE_DEFAULT = BigDecimal.valueOf(2);

    private final CountSheetRepository countSheetRepository;
    private final CountSheetItemRepository countSheetItemRepository;
    private final ProductRepository productRepository;
    private final SaleItemRepository saleItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AuditLogService auditLogService;

    public StockCountService(CountSheetRepository countSheetRepository,
                             CountSheetItemRepository countSheetItemRepository,
                             ProductRepository productRepository,
                             SaleItemRepository saleItemRepository,
                             StockMovementRepository stockMovementRepository,
                             UserRepository userRepository,
                             SystemSettingRepository systemSettingRepository,
                             AuditLogService auditLogService) {
        this.countSheetRepository = countSheetRepository;
        this.countSheetItemRepository = countSheetItemRepository;
        this.productRepository = productRepository;
        this.saleItemRepository = saleItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.userRepository = userRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------
    // ABC classification
    // ------------------------------------------------------------------

    /** Reclassifies products by revenue contribution over the last 90 days. */
    public int refreshAbcClasses() {
        LocalDateTime from = LocalDateTime.now().minusDays(90);
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
        LocalDate today = LocalDate.now();
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
        sheet.setScheduledDate(LocalDate.now());
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
        CountSheet sheet = getSheet(id);
        if (sheet.getStatus() == CountSheet.CountStatus.DRAFT) {
            sheet.setStatus(CountSheet.CountStatus.IN_PROGRESS);
            sheet.setStartedAt(LocalDateTime.now());
            countSheetRepository.save(sheet);
        }
        return toResponse(sheet, false);
    }

    @Transactional(readOnly = true)
    public Page<CountSheetResponse> list(CountSheet.CountStatus status, Pageable pageable) {
        Page<CountSheet> page = status != null
                ? countSheetRepository.findByStatusInOrderByCreatedAtDesc(List.of(status), pageable)
                : countSheetRepository.findAllByOrderByCreatedAtDesc(pageable);
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
        CountSheet sheet = getSheet(id);
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
        for (CountSheetItem item : items) {
            CountSubmitRequest.Entry entry = entries.get(item.getId());
            if (entry == null || entry.getCountedQuantity() == null) {
                throw new BusinessException("Every item must be counted before submitting");
            }
            applyCountEntry(item, entry.getCountedQuantity(), entry.getReasonCode(), entry.getNote());
        }

        sheet.setStatus(CountSheet.CountStatus.PENDING_REVIEW);
        sheet.setSubmittedAt(LocalDateTime.now());
        sheet.setSubmittedBy(userId);
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            sheet.setNotes(request.getNotes());
        }
        recalculateVarianceValue(sheet, items);

        if (isWithinAutoApproveThreshold(sheet, items)) {
            applySheet(sheet, userId);
        } else {
            countSheetRepository.save(sheet);
        }
        return toResponse(sheet, false);
    }

    public CountSheetResponse approve(Long id, Long userId) {
        CountSheet sheet = getSheet(id);
        if (sheet.getStatus() != CountSheet.CountStatus.PENDING_REVIEW) {
            throw new BusinessException("Only a pending-review sheet can be approved");
        }
        List<CountSheetItem> items = countSheetItemRepository.findWithProductBySheetId(id);
        if (items.stream().anyMatch(item -> item.getCountedQuantity() == null)) {
            throw new BusinessException("Count sheet still has uncounted items");
        }
        applySheet(sheet, userId);
        return toResponse(sheet, false);
    }

    public CountSheetResponse reject(Long id, Long userId, String reason) {
        CountSheet sheet = getSheet(id);
        if (sheet.getStatus() != CountSheet.CountStatus.PENDING_REVIEW
                && sheet.getStatus() != CountSheet.CountStatus.IN_PROGRESS) {
            throw new BusinessException("This count sheet can no longer be rejected");
        }
        sheet.setStatus(CountSheet.CountStatus.CANCELLED);
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
        item.setCountedAt(LocalDateTime.now());
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
        BigDecimal threshold = systemSettingRepository.findBySettingKey("stockCountAutoApproveThreshold")
                .map(setting -> {
                    try {
                        return new BigDecimal(setting.getSettingValue());
                    } catch (Exception ex) {
                        return AUTO_APPROVE_DEFAULT;
                    }
                })
                .orElse(AUTO_APPROVE_DEFAULT);
        for (CountSheetItem item : items) {
            if (item.getVariance() == null) {
                return false;
            }
            if (BigDecimal.valueOf(Math.abs(item.getVariance())).compareTo(threshold) > 0) {
                return false;
            }
        }
        return true;
    }

    private void applySheet(CountSheet sheet, Long userId) {
        List<CountSheetItem> items = countSheetItemRepository.findWithProductBySheetId(sheet.getId());
        User user = userRepository.findById(userId).orElse(null);
        LocalDate today = LocalDate.now();
        for (CountSheetItem item : items) {
            if (item.getCountedQuantity() == null) {
                continue;
            }
            int variance = item.getVariance() != null ? item.getVariance() : 0;
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + item.getProduct().getId()));
            int newQuantity = product.getStockQuantity() + variance;
            if (newQuantity < 0) {
                throw new BusinessException("Count variance would drive " + product.getName()
                        + " stock below zero");
            }
            product.setStockQuantity(newQuantity);
            product.setLastCountDate(today);
            productRepository.save(product);

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
                movement.setMovementDate(LocalDateTime.now());
                stockMovementRepository.save(movement);
            }
        }
        sheet.setStatus(CountSheet.CountStatus.APPROVED);
        sheet.setApprovedAt(LocalDateTime.now());
        sheet.setApprovedBy(userId);
        countSheetRepository.save(sheet);
        auditLogService.logAction(userId, "STOCK_COUNT_APPROVE",
                "Count sheet approved and stock adjusted: " + sheet.getSheetNumber(),
                "CountSheet", sheet.getId(), null, String.valueOf(sheet.getVarianceValue()));
    }

    // ------------------------------------------------------------------
    // Idempotent offline sync
    // ------------------------------------------------------------------

    public CountSheetResponse sync(SyncCountSheetRequest request, Long userId) {
        Optional<CountSheet> existing = countSheetRepository.findBySyncKey(request.getSyncKey());
        if (existing.isPresent()) {
            return toResponse(existing.get(), false);
        }

        CountSheet sheet = new CountSheet();
        sheet.setSheetNumber(nextSheetNumber());
        sheet.setSyncKey(request.getSyncKey());
        sheet.setCountType(parseType(request.getCountType()));
        sheet.setStatus(CountSheet.CountStatus.IN_PROGRESS);
        sheet.setScheduledDate(request.getSubmittedAt() != null
                ? request.getSubmittedAt().toLocalDate() : LocalDate.now());
        sheet.setStartedAt(request.getSubmittedAt() != null ? request.getSubmittedAt() : LocalDateTime.now());
        sheet.setSubmittedAt(request.getSubmittedAt() != null ? request.getSubmittedAt() : LocalDateTime.now());
        sheet.setSubmittedBy(userId);
        sheet.setCreatedBy(userId);
        sheet.setNotes(request.getNotes());

        List<CountSheetItem> items = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SyncCountSheetRequest.Entry entry : request.getItems()) {
            if (entry.getProductId() == null || !seen.add(entry.getProductId())) {
                throw new BusinessException("Every sync item needs a unique product id");
            }
            Product product = productRepository.findById(entry.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + entry.getProductId()));
            CountSheetItem item = new CountSheetItem();
            item.setCountSheet(sheet);
            item.setProduct(product);
            item.setSystemQuantity(entry.getSystemQuantity() != null
                    ? entry.getSystemQuantity()
                    : (product.getStockQuantity() != null ? product.getStockQuantity() : 0));
            applyCountEntry(item, entry.getCountedQuantity() != null ? entry.getCountedQuantity() : 0,
                    entry.getReasonCode(), entry.getNote());
            sheet.getItems().add(item);
            items.add(item);
        }
        if (items.isEmpty()) {
            throw new BusinessException("Sync payload has no count items");
        }
        recalculateVarianceValue(sheet, items);
        if (isWithinAutoApproveThreshold(sheet, items)) {
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

    private CountSheetResponse toResponse(CountSheet sheet, boolean blind) {
        CountSheetResponse response = new CountSheetResponse();
        response.setId(sheet.getId());
        response.setSheetNumber(sheet.getSheetNumber());
        response.setStatus(sheet.getStatus().name());
        response.setCountType(sheet.getCountType().name());
        response.setScheduledDate(sheet.getScheduledDate());
        response.setStartedAt(sheet.getStartedAt());
        response.setSubmittedAt(sheet.getSubmittedAt());
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
            dto.setCountedQuantity(item.getCountedQuantity());
            dto.setVariance(blind ? null : item.getVariance());
            dto.setReasonCode(item.getReasonCode());
            dto.setValuationAmount(blind ? null : item.getValuationAmount());
            dto.setNote(item.getNote());
            response.getItems().add(dto);
        }
        return response;
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
        LocalDate today = LocalDate.now();
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
