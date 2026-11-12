package com.bms.service;

import com.bms.dto.request.CloseDrawerSessionRequest;
import com.bms.dto.request.DrawerMovementRequest;
import com.bms.dto.response.DrawerMovementResponse;
import com.bms.dto.response.DrawerSessionResponse;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.CashDrawerRepository;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import com.bms.util.CashVarianceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Owns the physical cash drawer lifecycle. Employee shifts attach to a drawer
 * session; the expected cash and the variance always belong to the session,
 * never to an individual employee.
 *
 * <p>Expected cash is derived from the signed movement ledger every time it is
 * read. Storing a running total would let the report and the drawer disagree
 * after any failed or replayed movement.
 */
@Service
@Transactional
public class CashDrawerSessionService {

    public static final String SETTING_ALLOW_SHARED = "allow_shared_cash_drawer";
    public static final String SETTING_REQUIRE_APPROVAL = "require_manager_approval_shared_drawer";
    public static final String SETTING_BLIND_CLOSE = "blind_drawer_close";

    @Autowired
    private CashDrawerRepository cashDrawerRepository;

    @Autowired
    private CashDrawerSessionRepository cashDrawerSessionRepository;

    @Autowired
    private CashDrawerMovementRepository cashDrawerMovementRepository;

    @Autowired
    private CashShiftRepository cashShiftRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private AuditLogService auditLogService;

    // ------------------------------------------------------------------
    // Drawer configuration
    // ------------------------------------------------------------------

    public boolean isSharedDrawerAllowed() {
        return getBooleanSetting(SETTING_ALLOW_SHARED, false);
    }

    public boolean isManagerApprovalRequired() {
        return getBooleanSetting(SETTING_REQUIRE_APPROVAL, false);
    }

    public boolean isBlindCloseEnabled() {
        return getBooleanSetting(SETTING_BLIND_CLOSE, true);
    }

    private boolean getBooleanSetting(String key, boolean fallback) {
        return systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue)
                .map(String::trim)
                .map(String::toLowerCase)
                .map(value -> "true".equals(value) || "1".equals(value) || "yes".equals(value))
                .orElse(fallback);
    }

    /**
     * Returns the shop's physical drawer, creating the default "Main Drawer"
     * lazily so an upgraded database works even before the seeder has run.
     */
    public CashDrawer getDefaultDrawer() {
        return cashDrawerRepository.findFirstByIsActiveTrueAndDeletedAtIsNullOrderByIdAsc()
                .orElseGet(() -> {
                    CashDrawer drawer = new CashDrawer();
                    drawer.setName("Main Drawer");
                    drawer.setCode("MAIN");
                    drawer.setIsActive(true);
                    return cashDrawerRepository.save(drawer);
                });
    }

    public CashDrawer getDrawer(Long drawerId) {
        if (drawerId == null) {
            return getDefaultDrawer();
        }
        CashDrawer drawer = cashDrawerRepository.findById(drawerId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash drawer not found"));
        if (drawer.getDeletedAt() != null || Boolean.FALSE.equals(drawer.getIsActive())) {
            throw new BusinessException("Cash drawer is not active");
        }
        return drawer;
    }

    public List<CashDrawer> getActiveDrawers() {
        List<CashDrawer> drawers = cashDrawerRepository
                .findByIsActiveTrueAndDeletedAtIsNullOrderByIdAsc();
        if (drawers.isEmpty()) {
            return List.of(getDefaultDrawer());
        }
        return drawers;
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    public CashDrawerSession getOpenSession(Long drawerId) {
        return cashDrawerSessionRepository
                .findByDrawerIdAndStatus(drawerId, DrawerSessionStatus.OPEN)
                .orElse(null);
    }

    public CashDrawerSession getOpenSessionForUpdate(Long drawerId) {
        // The caller normally holds the drawer row lock already, which
        // serialises session creation for the same physical drawer.
        return getOpenSession(drawerId);
    }

    /**
     * Opens a physical drawer session. The caller must already have validated
     * the employee's cash authority. The opening float is recorded as the first
     * ledger movement so expected cash is always a pure sum of movements.
     */
    public CashDrawerSession openSession(CashDrawer drawer, BigDecimal openingFloat,
                                         User openedBy, Long employeeShiftId) {
        CashDrawer locked = cashDrawerRepository.findByIdForUpdate(drawer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cash drawer not found"));

        if (getOpenSession(locked.getId()) != null) {
            throw new BusinessException("DRAWER_ALREADY_OPEN: This drawer already has an open session.");
        }

        CashDrawerSession session = new CashDrawerSession();
        session.setDrawer(locked);
        session.setOpenedBy(openedBy);
        session.setOpenedAt(LocalDateTime.now());
        session.setStatus(DrawerSessionStatus.OPEN);
        session.setOpeningFloat(nullSafe(openingFloat));
        session.setBlindClose(isBlindCloseEnabled());
        CashDrawerSession saved = cashDrawerSessionRepository.save(session);

        if (saved.getOpeningFloat().compareTo(BigDecimal.ZERO) != 0) {
            CashDrawerMovement movement = new CashDrawerMovement();
            movement.setDrawerSession(saved);
            movement.setMovementType(DrawerMovementType.OPENING_FLOAT);
            movement.setAmount(saved.getOpeningFloat());
            movement.setReferenceType("DRAWER_SESSION");
            movement.setReferenceId(saved.getId());
            movement.setPerformedBy(openedBy);
            if (employeeShiftId != null) {
                cashShiftRepository.findById(employeeShiftId)
                        .ifPresent(movement::setEmployeeShift);
            }
            movement.setReason("Opening float");
            cashDrawerMovementRepository.save(movement);
        }

        auditLogService.logAction(openedBy != null ? openedBy.getId() : null, "DRAWER_OPEN",
                "Drawer " + locked.getName() + " opened with float " + saved.getOpeningFloat(),
                "CashDrawerSession", saved.getId(), null, null);
        return saved;
    }

    public CashDrawerSession getSession(Long id) {
        return cashDrawerSessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Drawer session not found"));
    }

    public CashDrawerSession getOpenSessionByIdForUpdate(Long id) {
        CashDrawerSession session = cashDrawerSessionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Drawer session not found"));
        if (session.getStatus() != DrawerSessionStatus.OPEN) {
            throw new BusinessException("Drawer session is already closed");
        }
        return session;
    }

    public BigDecimal getExpectedCash(Long sessionId) {
        BigDecimal expected = cashDrawerMovementRepository.sumAmountBySession(sessionId);
        return nullSafe(expected);
    }

    public DrawerSessionResponse getSessionResponse(Long id) {
        CashDrawerSession session = getSession(id);
        return toResponse(session, true, false);
    }

    @Transactional(readOnly = true)
    public Page<DrawerSessionResponse> getSessionHistory(LocalDateTime start, LocalDateTime end,
                                                         Pageable pageable) {
        LocalDateTime from = start != null ? start : LocalDateTime.now().minusMonths(1);
        LocalDateTime to = end != null ? end : LocalDateTime.now();
        return cashDrawerSessionRepository.findByDateRange(from, to, pageable)
                .map(session -> toResponse(session, false, true));
    }

    public List<DrawerSessionResponse> getActiveSessions() {
        return cashDrawerSessionRepository.findByStatusOrderByOpenedAtAsc(DrawerSessionStatus.OPEN)
                .stream()
                .map(session -> toResponse(session, false, false))
                .collect(Collectors.toList());
    }

    /**
     * Blind close. Expected cash is computed only after the counted amount has
     * been supplied, so it cannot be used to "count to the number".
     */
    public DrawerSessionResponse closeSession(Long sessionId, CloseDrawerSessionRequest request,
                                              Long userId, boolean managerOrAdmin) {
        CashDrawerSession session = cashDrawerSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Drawer session not found"));
        if (session.getStatus() != DrawerSessionStatus.OPEN) {
            throw new BusinessException("Drawer session is already closed");
        }

        User closer = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isOwner = session.getOpenedBy() != null
                && Objects.equals(session.getOpenedBy().getId(), userId);
        if (!isOwner && !managerOrAdmin) {
            throw new BusinessException("Only the drawer owner or a manager can close the drawer.");
        }

        List<CashShift> openShifts = cashShiftRepository
                .findByDrawerSessionIdAndStatus(sessionId, "OPEN");
        boolean otherEmployeesAttached = openShifts.stream()
                .anyMatch(shift -> !Objects.equals(shift.getCashierId(), userId));
        if (otherEmployeesAttached && !managerOrAdmin) {
            throw new BusinessException(
                    "Other employees are still attached to this drawer. A manager must close or transfer the drawer.");
        }
        if (otherEmployeesAttached && Boolean.TRUE.equals(request.getForceClose())
                && (request.getForceReason() == null || request.getForceReason().isBlank())) {
            throw new BusinessException("A force-close reason is required");
        }

        BigDecimal expected = getExpectedCash(sessionId);
        BigDecimal counted = nullSafe(request.getCountedCash());
        BigDecimal variance = CashVarianceUtil.variance(counted, expected);

        if (!managerOrAdmin && CashVarianceUtil.exceedsTolerance(variance, CashVarianceUtil.DEFAULT_TOLERANCE)) {
            throw new BusinessException(
                    "Drawer variance exceeds the allowed tolerance. Manager approval is required to close.");
        }

        LocalDateTime closedAt = LocalDateTime.now();
        session.setStatus(DrawerSessionStatus.CLOSED);
        session.setClosedAt(closedAt);
        session.setClosedBy(closer);
        session.setExpectedCash(expected);
        session.setCountedCash(counted);
        session.setVarianceAmount(variance);
        session.setClosingNote(request.getClosingNote());
        if (Boolean.TRUE.equals(request.getForceClose())) {
            session.setForceClosed(true);
            session.setForceCloseReason(request.getForceReason());
        }
        cashDrawerSessionRepository.save(session);

        // Closing the drawer ends every employee shift still attached to it.
        for (CashShift shift : openShifts) {
            shift.setStatus("CLOSED");
            shift.setClosingTime(closedAt);
            shift.setClosedBy(userId);
            boolean ownerShift = Objects.equals(shift.getCashierId(), session.getOpenedBy() != null
                    ? session.getOpenedBy().getId() : null);
            if (ownerShift) {
                // The counted cash and variance describe the whole drawer, so
                // they are attached to the owner's shift only. Other employees
                // get their own sales summary without a misleading variance.
                shift.setClosingAmount(counted);
                shift.setExpectedAmount(expected);
                shift.setVariance(variance);
            }
            String note = request.getClosingNote();
            shift.setNotes(note != null && !note.isBlank()
                    ? note
                    : "Closed with drawer session #" + sessionId);
            if (Boolean.TRUE.equals(request.getForceClose())) {
                shift.setForceClosed(true);
                shift.setForceCloseReason(request.getForceReason());
            }
            cashShiftRepository.save(shift);
        }

        auditLogService.logAction(userId, "DRAWER_CLOSE",
                "Drawer closed. Expected: " + expected + ", Counted: " + counted
                        + ", Variance: " + variance
                        + CashVarianceUtil.describeVariance(variance, expected),
                "CashDrawerSession", sessionId, null, null);

        return toResponse(session, true, true);
    }

    /**
     * Records a non-sale drawer operation. The request amount is a magnitude;
     * this method applies the financial sign so every consumer can rely on the
     * stored amount being signed.
     */
    public DrawerMovementResponse performOperation(Long sessionId, DrawerMovementRequest request,
                                                   Long userId, boolean managerOrAdmin) {
        CashDrawerSession session = cashDrawerSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Drawer session not found"));
        if (session.getStatus() != DrawerSessionStatus.OPEN) {
            throw new BusinessException("Drawer session is already closed");
        }

        User performer = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        CashShift employeeShift = cashShiftRepository
                .findByCashierIdAndStatus(userId, "OPEN").orElse(null);
        boolean cashShiftAttached = employeeShift != null
                && employeeShift.getShiftType() == com.bms.entity.ShiftType.CASH_DRAWER
                && employeeShift.getDrawerSession() != null
                && Objects.equals(employeeShift.getDrawerSession().getId(), sessionId);

        if (!managerOrAdmin && !cashShiftAttached) {
            throw new BusinessException("DRAWER_ACCESS_DENIED: An active cash drawer shift is required.");
        }

        DrawerMovementType type = parseMovementType(request.getMovementType());
        BigDecimal amount = nullSafe(request.getAmount());
        if (type == DrawerMovementType.ADJUSTMENT && !managerOrAdmin) {
            throw new BusinessException("Only a manager can adjust drawer cash");
        }
        if (type == DrawerMovementType.NO_SALE_OPEN && amount.compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("A no-sale open has no financial amount");
        }
        if ((type == DrawerMovementType.PAY_OUT || type == DrawerMovementType.ADJUSTMENT)
                && (request.getReason() == null || request.getReason().isBlank())) {
            throw new BusinessException("A reason is required for this drawer operation");
        }

        BigDecimal signedAmount;
        switch (type) {
            case SAFE_DROP:
            case PAY_OUT:
                signedAmount = amount.negate();
                break;
            case PAY_IN:
                signedAmount = amount;
                break;
            case ADJUSTMENT:
                signedAmount = "DECREASE".equalsIgnoreCase(request.getDirection())
                        ? amount.negate() : amount;
                break;
            case NO_SALE_OPEN:
                signedAmount = BigDecimal.ZERO;
                break;
            default:
                throw new BusinessException("Unsupported drawer operation: " + type);
        }

        CashDrawerMovement movement = new CashDrawerMovement();
        movement.setDrawerSession(session);
        movement.setMovementType(type);
        movement.setAmount(signedAmount);
        movement.setReferenceType("MANUAL");
        movement.setReferenceId(null);
        movement.setPerformedBy(performer);
        movement.setEmployeeShift(cashShiftAttached ? employeeShift : null);
        movement.setReason(buildReason(request));
        CashDrawerMovement saved = cashDrawerMovementRepository.save(movement);

        auditLogService.logAction(userId, "DRAWER_" + type.name(),
                type.name() + " " + signedAmount + " on drawer session " + sessionId
                        + (request.getReason() != null ? " | " + request.getReason() : ""),
                "CashDrawerMovement", saved.getId(), null, null);

        return toMovementResponse(saved);
    }

    private String buildReason(DrawerMovementRequest request) {
        String reason = request.getReason();
        if (request.getExternalReference() != null && !request.getExternalReference().isBlank()) {
            reason = (reason == null || reason.isBlank() ? "" : reason + " | ")
                    + "Ref: " + request.getExternalReference();
        }
        return reason;
    }

    private DrawerMovementType parseMovementType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Movement type is required");
        }
        try {
            return DrawerMovementType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown drawer movement type: " + raw);
        }
    }

    /**
     * Creates a movement as part of a sale/refund. Used by SaleService and
     * SaleReturnService; the session must already be open.
     */
    public CashDrawerMovement recordFinancialMovement(CashDrawerSession session,
                                                      DrawerMovementType type,
                                                      BigDecimal signedAmount,
                                                      String referenceType,
                                                      Long referenceId,
                                                      User performedBy,
                                                      CashShift employeeShift,
                                                      String reason) {
        if (session == null) {
            throw new BusinessException("An open cash drawer session is required for cash movements");
        }
        if (session.getStatus() != DrawerSessionStatus.OPEN) {
            throw new BusinessException("The cash drawer session is closed");
        }
        CashDrawerMovement movement = new CashDrawerMovement();
        movement.setDrawerSession(session);
        movement.setMovementType(type);
        movement.setAmount(signedAmount);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setPerformedBy(performedBy);
        movement.setEmployeeShift(employeeShift);
        movement.setReason(reason);
        return cashDrawerMovementRepository.save(movement);
    }

    public void attachMovementToShift(CashDrawerMovement movement, CashShift shift) {
        if (movement != null && shift != null) {
            movement.setEmployeeShift(shift);
            cashDrawerMovementRepository.save(movement);
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    public DrawerSessionResponse toResponse(CashDrawerSession session, boolean includeMovements,
                                            boolean revealExpected) {
        DrawerSessionResponse response = new DrawerSessionResponse();
        response.setId(session.getId());
        if (session.getDrawer() != null) {
            response.setDrawerId(session.getDrawer().getId());
            response.setDrawerName(session.getDrawer().getName());
            response.setDrawerCode(session.getDrawer().getCode());
        }
        if (session.getOpenedBy() != null) {
            response.setOpenedById(session.getOpenedBy().getId());
            response.setOpenedByName(displayName(session.getOpenedBy()));
        }
        if (session.getClosedBy() != null) {
            response.setClosedById(session.getClosedBy().getId());
            response.setClosedByName(displayName(session.getClosedBy()));
        }
        response.setOpenedAt(session.getOpenedAt());
        response.setClosedAt(session.getClosedAt());
        response.setStatus(session.getStatus() != null ? session.getStatus().name() : null);
        response.setOpeningFloat(nullSafe(session.getOpeningFloat()));
        response.setCountedCash(session.getCountedCash());
        response.setVariance(session.getVarianceAmount());
        response.setClosingNote(session.getClosingNote());
        response.setBlindClose(Boolean.TRUE.equals(session.getBlindClose()));
        response.setForceClosed(Boolean.TRUE.equals(session.getForceClosed()));
        response.setForceCloseReason(session.getForceCloseReason());

        boolean hideExpected = session.getStatus() == DrawerSessionStatus.OPEN
                && Boolean.TRUE.equals(session.getBlindClose())
                && !revealExpected;
        response.setExpectedHidden(hideExpected);

        BigDecimal expected = revealExpected && session.getExpectedCash() != null
                ? session.getExpectedCash()
                : getExpectedCash(session.getId());
        response.setExpectedCash(hideExpected ? null : expected);

        Map<DrawerMovementType, BigDecimal> summary = movementSummary(session.getId());
        response.setCashSales(summary.getOrDefault(DrawerMovementType.CASH_SALE, BigDecimal.ZERO));
        response.setCashRefunds(summary.getOrDefault(DrawerMovementType.CASH_REFUND, BigDecimal.ZERO));
        response.setSafeDrops(summary.getOrDefault(DrawerMovementType.SAFE_DROP, BigDecimal.ZERO));
        response.setPayIns(summary.getOrDefault(DrawerMovementType.PAY_IN, BigDecimal.ZERO));
        response.setPayOuts(summary.getOrDefault(DrawerMovementType.PAY_OUT, BigDecimal.ZERO));
        response.setAdjustments(summary.getOrDefault(DrawerMovementType.ADJUSTMENT, BigDecimal.ZERO));

        List<CashDrawerMovement> movements =
                cashDrawerMovementRepository.findByDrawerSessionIdOrderByOccurredAtAsc(session.getId());
        response.setMovementCount(movements.size());
        Map<String, BigDecimal> breakdown = new HashMap<>();
        summary.forEach((type, amount) -> breakdown.put(type.name(), amount));
        response.setMovementBreakdown(breakdown);

        if (includeMovements) {
            response.setMovements(movements.stream()
                    .map(this::toMovementResponse)
                    .collect(Collectors.toList()));
        }
        response.setContributingEmployees(buildEmployeeContributions(session.getId()));
        return response;
    }

    private Map<DrawerMovementType, BigDecimal> movementSummary(Long sessionId) {
        Map<DrawerMovementType, BigDecimal> summary = new EnumMap<>(DrawerMovementType.class);
        for (Object[] row : cashDrawerMovementRepository.summariseByType(sessionId)) {
            DrawerMovementType type = (DrawerMovementType) row[0];
            BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            summary.put(type, amount);
        }
        return summary;
    }

    private List<DrawerSessionResponse.EmployeeCashContribution> buildEmployeeContributions(Long sessionId) {
        Map<Long, DrawerSessionResponse.EmployeeCashContribution> byShift = new HashMap<>();
        for (Object[] row : cashDrawerMovementRepository.summariseByEmployee(sessionId)) {
            Long shiftId = (Long) row[0];
            Long cashierId = (Long) row[1];
            DrawerMovementType type = (DrawerMovementType) row[2];
            BigDecimal amount = row[3] != null ? (BigDecimal) row[3] : BigDecimal.ZERO;

            DrawerSessionResponse.EmployeeCashContribution contribution =
                    byShift.computeIfAbsent(shiftId, id -> {
                        DrawerSessionResponse.EmployeeCashContribution created =
                                new DrawerSessionResponse.EmployeeCashContribution();
                        created.setEmployeeShiftId(id);
                        created.setUserId(cashierId);
                        created.setCashSales(BigDecimal.ZERO);
                        created.setCashRefunds(BigDecimal.ZERO);
                        return created;
                    });
            if (type == DrawerMovementType.CASH_SALE) {
                contribution.setCashSales(contribution.getCashSales().add(amount));
            } else if (type == DrawerMovementType.CASH_REFUND) {
                contribution.setCashRefunds(contribution.getCashRefunds().add(amount));
            }
        }
        if (!byShift.isEmpty()) {
            Map<Long, String> names = userRepository.findAllById(
                            byShift.values().stream()
                                    .map(DrawerSessionResponse.EmployeeCashContribution::getUserId)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toSet()))
                    .stream()
                    .collect(Collectors.toMap(User::getId, this::displayName));
            byShift.values().forEach(contribution ->
                    contribution.setEmployeeName(names.getOrDefault(contribution.getUserId(), "Unknown")));
        }
        return new ArrayList<>(byShift.values());
    }

    public DrawerMovementResponse toMovementResponse(CashDrawerMovement movement) {
        DrawerMovementResponse response = new DrawerMovementResponse();
        response.setId(movement.getId());
        if (movement.getDrawerSession() != null) {
            response.setDrawerSessionId(movement.getDrawerSession().getId());
        }
        response.setMovementType(movement.getMovementType() != null
                ? movement.getMovementType().name() : null);
        response.setAmount(movement.getAmount());
        response.setReferenceType(movement.getReferenceType());
        response.setReferenceId(movement.getReferenceId());
        if (movement.getPerformedBy() != null) {
            response.setPerformedById(movement.getPerformedBy().getId());
            response.setPerformedByName(displayName(movement.getPerformedBy()));
        }
        CashShift shift = movement.getEmployeeShift();
        if (shift != null) {
            response.setEmployeeShiftId(shift.getId());
            response.setCashierId(shift.getCashierId());
            userRepository.findById(shift.getCashierId())
                    .ifPresent(user -> response.setCashierName(displayName(user)));
        }
        response.setReason(movement.getReason());
        response.setOccurredAt(movement.getOccurredAt());
        return response;
    }

    private String displayName(User user) {
        if (user == null) return null;
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        String name = (first + " " + last).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
