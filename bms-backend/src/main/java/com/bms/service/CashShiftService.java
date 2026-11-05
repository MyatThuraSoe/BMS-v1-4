package com.bms.service;

import com.bms.dto.request.CloseDrawerSessionRequest;
import com.bms.dto.request.CloseShiftRequest;
import com.bms.dto.request.OpenShiftRequest;
import com.bms.dto.response.CashShiftResponse;
import com.bms.dto.response.DrawerSessionResponse;
import com.bms.dto.response.ShiftStartContextResponse;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Sale;
import com.bms.entity.ShiftType;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.UserRepository;
import com.bms.util.CashVarianceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Employee work shifts. A shift may be cash-drawer backed or non-cash; only a
 * cash drawer shift with an open drawer session may accept cash.
 */
@Service
@Transactional
public class CashShiftService {

    @Autowired
    private CashShiftRepository cashShiftRepository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private SaleReturnRepository saleReturnRepository;

    @Autowired
    private CashDrawerMovementRepository cashDrawerMovementRepository;

    @Autowired
    private CashDrawerSessionRepository cashDrawerSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogService auditLogService;

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    public CashShiftResponse openShift(Long cashierId, OpenShiftRequest request) {
        // Serialise concurrent open requests for the same employee.
        User cashier = userRepository.findByIdForUpdate(cashierId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (cashShiftRepository.existsByCashierIdAndStatus(cashierId, "OPEN")) {
            throw new BusinessException("You already have an open shift. Close it before starting a new one.");
        }

        ShiftType shiftType = parseShiftType(request.getShiftType());
        CashShift shift = new CashShift();
        shift.setCashierId(cashierId);
        shift.setShiftType(shiftType);
        shift.setOpeningTime(LocalDateTime.now());
        shift.setStatus("OPEN");
        shift.setForceClosed(false);

        if (shiftType == ShiftType.CASH_DRAWER) {
            CashDrawer drawer = cashDrawerSessionService.getDrawer(request.getDrawerId());
            CashDrawerSession existing = cashDrawerSessionService.getOpenSession(drawer.getId());

            boolean managerOrAdmin = isManagerOrAdmin(cashier);
            if (existing != null) {
                if (!cashDrawerSessionService.isSharedDrawerAllowed()) {
                    String owner = existing.getOpenedBy() != null
                            ? displayName(existing.getOpenedBy()) : "another employee";
                    throw new BusinessException(
                            "DRAWER_ALREADY_OPEN: " + drawer.getName() + " is already open by " + owner
                                    + ". Open a non-cash shift, or ask a manager to enable shared drawer mode.");
                }
                if (cashDrawerSessionService.isManagerApprovalRequired() && !managerOrAdmin) {
                    verifyManagerApproval(request.getApproverUsername(), request.getApproverPassword());
                }
                shift.setDrawerSession(existing);
                shift.setOpeningAmount(existing.getOpeningFloat());
            } else {
                BigDecimal openingFloat = request.resolvedOpeningFloat();
                CashDrawerSession session = cashDrawerSessionService.openSession(
                        drawer, openingFloat, cashier, null);
                shift.setDrawerSession(session);
                shift.setOpeningAmount(session.getOpeningFloat());
            }
        } else {
            // Non-cash and supervisor shifts never hold a float or a drawer.
            shift.setOpeningAmount(BigDecimal.ZERO);
        }

        shift.setNotes(request.getNotes());
        CashShift saved = cashShiftRepository.save(shift);

        if (saved.getDrawerSession() != null) {
            // Attach the opening float ledger line to this employee shift so a
            // drawer report can attribute every line.
            List<CashDrawerMovement> movements = cashDrawerMovementRepository
                    .findByDrawerSessionIdAndMovementTypeOrderByOccurredAtAsc(
                            saved.getDrawerSession().getId(), DrawerMovementType.OPENING_FLOAT);
            for (CashDrawerMovement movement : movements) {
                if (movement.getEmployeeShift() == null) {
                    cashDrawerSessionService.attachMovementToShift(movement, saved);
                }
            }
        }

        auditLogService.logAction(cashierId, "SHIFT_OPEN",
                shiftType + " shift opened"
                        + (saved.getDrawerSession() != null
                        ? " on " + saved.getDrawerSession().getDrawer().getName()
                        + " with float " + saved.getOpeningAmount()
                        : " (no drawer)"),
                "CashShift", saved.getId(), null, null);

        return getShiftById(saved.getId());
    }

    private ShiftType parseShiftType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ShiftType.CASH_DRAWER;
        }
        String normalised = raw.trim().toUpperCase();
        if ("CASH".equals(normalised)) {
            return ShiftType.CASH_DRAWER;
        }
        try {
            return ShiftType.valueOf(normalised);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown shift type: " + raw);
        }
    }

    private void verifyManagerApproval(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException("MANAGER_APPROVAL_REQUIRED: Manager credentials are required to join this drawer.");
        }
        User approver = userRepository.findActiveByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BusinessException("Invalid manager credentials"));
        if (!isManagerOrAdmin(approver)) {
            throw new BusinessException("The approver must be a manager or administrator");
        }
        if (!passwordEncoder.matches(password, approver.getPassword())) {
            throw new BusinessException("Invalid manager credentials");
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public CashShiftResponse getCurrentShift(Long cashierId) {
        CashShift shift = cashShiftRepository.findByCashierIdAndStatus(cashierId, "OPEN").orElse(null);
        if (shift == null) {
            return null;
        }
        return toResponse(shift, true);
    }

    public CashShiftResponse getShiftById(Long shiftId) {
        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        CashShiftResponse response = toResponse(shift, true);

        List<Sale> sales = new ArrayList<>(saleRepository.findByEmployeeShiftId(shiftId));
        if (sales.isEmpty()) {
            // Historical rows created before employeeShiftId existed.
            sales = new ArrayList<>(saleRepository.findByCashShiftId(shiftId));
        }
        response.setSales(sales.stream()
                .sorted(Comparator.comparing(Sale::getSaleDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(sale -> {
                    CashShiftResponse.SaleReference ref = new CashShiftResponse.SaleReference();
                    ref.setId(sale.getId());
                    ref.setInvoiceNumber(sale.getInvoiceNumber());
                    ref.setTotalAmount(sale.getTotalAmount());
                    ref.setSaleDate(sale.getSaleDate());
                    return ref;
                })
                .collect(Collectors.toList()));
        return response;
    }

    @Transactional(readOnly = true)
    public Page<CashShiftResponse> getShiftHistory(Long cashierId, LocalDate startDate, LocalDate endDate,
                                                   Pageable pageable) {
        LocalDateTime startDateTime = startDate != null
                ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime endDateTime = endDate != null
                ? endDate.atTime(LocalTime.MAX) : LocalDateTime.now();

        Page<CashShift> shifts;
        if (cashierId != null) {
            shifts = cashShiftRepository.findByCashierIdsAndDateRange(
                    List.of(cashierId), startDateTime, endDateTime, pageable);
        } else {
            shifts = cashShiftRepository.findByDateRange(startDateTime, endDateTime, pageable);
        }

        Set<Long> cashierIds = shifts.getContent().stream()
                .map(CashShift::getCashierId)
                .collect(Collectors.toSet());
        Map<Long, String> namesById = userRepository.findAllById(cashierIds).stream()
                .collect(Collectors.toMap(User::getId, this::displayName));

        return shifts.map(shift -> toResponse(shift, namesById, false));
    }

    /**
     * Manager dashboard widget: every open employee shift, cash or not.
     */
    @Transactional(readOnly = true)
    public List<CashShiftResponse> getActiveShifts() {
        return cashShiftRepository.findByStatusOrderByOpeningTimeAsc("OPEN").stream()
                .map(shift -> toResponse(shift, false))
                .collect(Collectors.toList());
    }

    /**
     * Employee-shift report rows for a date window. Totals are per employee
     * shift; drawer cash and variance stay on the drawer report.
     */
    @Transactional(readOnly = true)
    public List<CashShiftResponse> getShiftReport(LocalDateTime start, LocalDateTime end,
                                                  Long cashierId, String shiftType) {
        ShiftType parsedType = null;
        if (shiftType != null && !shiftType.isBlank()) {
            parsedType = parseShiftType(shiftType);
        }
        return cashShiftRepository.findForReport(start, end, cashierId, parsedType).stream()
                .map(shift -> toResponse(shift, true))
                .collect(Collectors.toList());
    }

    /**
     * Data for the Start Shift screen; works before the caller has a shift.
     */
    public ShiftStartContextResponse getStartContext(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        CashDrawer drawer = cashDrawerSessionService.getDefaultDrawer();
        CashDrawerSession openSession = cashDrawerSessionService.getOpenSession(drawer.getId());

        boolean managerOrAdmin = isManagerOrAdmin(user);
        boolean allowShared = cashDrawerSessionService.isSharedDrawerAllowed();
        boolean approvalRequired = cashDrawerSessionService.isManagerApprovalRequired();

        ShiftStartContextResponse response = new ShiftStartContextResponse();
        response.setDrawerId(drawer.getId());
        response.setDrawerName(drawer.getName());
        response.setDrawerCode(drawer.getCode());
        response.setDrawerStatus(openSession != null ? "OPEN" : "CLOSED");
        response.setAllowSharedDrawer(allowShared);
        response.setManagerApprovalRequired(approvalRequired);
        response.setManagerOrAdmin(managerOrAdmin);
        response.setCanOpenNonCash(true);

        if (openSession == null) {
            response.setCanOpenCashDrawer(true);
            response.setCanJoinExisting(false);
            response.setMessage("The cash drawer is closed.");
        } else {
            response.setOpenDrawerSessionId(openSession.getId());
            if (openSession.getOpenedBy() != null) {
                response.setOpenedById(openSession.getOpenedBy().getId());
                response.setOpenedByName(displayName(openSession.getOpenedBy()));
            }
            response.setOpenedAt(openSession.getOpenedAt());
            response.setOpeningFloat(openSession.getOpeningFloat());
            response.setCanJoinExisting(allowShared);
            response.setCanOpenCashDrawer(allowShared);
            response.setMessage(allowShared
                    ? "The cash drawer is open. You can join it or open a non-cash shift."
                    : "The cash drawer is open. Only the attached employee can use it.");
        }
        return response;
    }

    // ------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------

    public CashShiftResponse closeShift(Long shiftId, CloseShiftRequest request, Long userId,
                                        boolean managerOrAdmin) {
        CashShift shift = cashShiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        if (!"OPEN".equals(shift.getStatus())) {
            throw new BusinessException("Shift is already closed");
        }

        boolean forceClose = Boolean.TRUE.equals(request.getForceClose());
        if (forceClose) {
            if (!managerOrAdmin) {
                throw new BusinessException("Only a manager can force-close a shift");
            }
            if (request.getForceReason() == null || request.getForceReason().isBlank()) {
                throw new BusinessException("A force-close reason is required");
            }
        }

        LocalDateTime closingTime = LocalDateTime.now();
        String note = request.resolvedNote();

        // Non-cash shifts close with no counted cash, expected cash or variance.
        if (shift.getShiftType() != ShiftType.CASH_DRAWER) {
            shift.setStatus("CLOSED");
            shift.setClosingTime(closingTime);
            shift.setClosedBy(userId);
            shift.setNotes(note);
            applyForceClose(shift, request);
            CashShift saved = cashShiftRepository.save(shift);
            auditLogService.logAction(userId, "SHIFT_CLOSE",
                    shift.getShiftType() + " shift closed with no cash reconciliation",
                    "CashShift", shiftId, null, null);
            return getShiftById(saved.getId());
        }

        CashDrawerSession session = shift.getDrawerSession();

        if (session != null && session.getStatus() == DrawerSessionStatus.OPEN) {
            BigDecimal counted = request.resolvedCountedCash();
            List<CashShift> openAttached = cashShiftRepository
                    .findByDrawerSessionIdAndStatus(session.getId(), "OPEN");
            boolean otherEmployeesAttached = openAttached.stream()
                    .anyMatch(other -> !Objects.equals(other.getId(), shift.getId()));

            boolean shouldCloseDrawer;
            if (request.getCloseDrawer() != null) {
                shouldCloseDrawer = request.getCloseDrawer();
            } else {
                // Legacy clients only send closingAmount; treat that as "close
                // the drawer too" when nobody else is attached.
                shouldCloseDrawer = counted != null && !otherEmployeesAttached;
            }

            if (shouldCloseDrawer) {
                if (counted == null) {
                    throw new BusinessException(
                            "Counted cash is required to close the cash drawer. The drawer stays open.");
                }
                CloseDrawerSessionRequest drawerClose = new CloseDrawerSessionRequest();
                drawerClose.setCountedCash(counted);
                drawerClose.setClosingNote(note);
                drawerClose.setForceClose(forceClose);
                drawerClose.setForceReason(request.getForceReason());
                cashDrawerSessionService.closeSession(
                        session.getId(), drawerClose, userId, managerOrAdmin);
                return getShiftById(shiftId);
            }

            // Closing one employee shift must not close a shared drawer.
            shift.setStatus("CLOSED");
            shift.setClosingTime(closingTime);
            shift.setClosedBy(userId);
            shift.setNotes(note != null ? note : "Shift closed; drawer remains open");
            applyForceClose(shift, request);
            CashShift saved = cashShiftRepository.save(shift);
            auditLogService.logAction(userId, "SHIFT_CLOSE",
                    "Cash drawer shift closed; drawer session " + session.getId() + " remains open",
                    "CashShift", shiftId, null, null);
            return getShiftById(saved.getId());
        }

        // The drawer was already closed, or this is a pre-upgrade shift.
        if (session != null) {
            boolean ownerShift = session.getOpenedBy() != null
                    && Objects.equals(session.getOpenedBy().getId(), shift.getCashierId());
            if (ownerShift) {
                shift.setClosingAmount(session.getCountedCash());
                shift.setExpectedAmount(session.getExpectedCash());
                shift.setVariance(session.getVarianceAmount());
            }
            shift.setNotes(note != null ? note : "Closed with drawer session #" + session.getId());
        } else {
            BigDecimal cashSales = nullSafe(saleRepository.sumNetCashSalesByShiftId(shiftId));
            BigDecimal returns = nullSafe(saleRepository.sumRefundsBetween(
                    shift.getOpeningTime(), closingTime));
            BigDecimal expected = CashVarianceUtil.expectedDrawer(
                    shift.getOpeningAmount(), cashSales, returns);
            BigDecimal counted = request.resolvedCountedCash() != null
                    ? request.resolvedCountedCash() : BigDecimal.ZERO;
            shift.setClosingAmount(counted);
            shift.setExpectedAmount(expected);
            shift.setVariance(CashVarianceUtil.variance(counted, expected));
            shift.setNotes(note);
        }

        shift.setStatus("CLOSED");
        shift.setClosingTime(closingTime);
        shift.setClosedBy(userId);
        applyForceClose(shift, request);
        CashShift saved = cashShiftRepository.save(shift);

        auditLogService.logAction(userId, "SHIFT_CLOSE",
                "Cash shift closed. Expected: " + shift.getExpectedAmount()
                        + ", Counted: " + shift.getClosingAmount()
                        + ", Variance: " + shift.getVariance()
                        + CashVarianceUtil.describeVariance(shift.getVariance(), shift.getExpectedAmount()),
                "CashShift", shiftId, null, null);
        return getShiftById(saved.getId());
    }

    private void applyForceClose(CashShift shift, CloseShiftRequest request) {
        if (Boolean.TRUE.equals(request.getForceClose())) {
            shift.setForceClosed(true);
            shift.setForceCloseReason(request.getForceReason());
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private CashShiftResponse toResponse(CashShift shift, boolean includeStats) {
        return toResponse(shift, null, includeStats);
    }

    private CashShiftResponse toResponse(CashShift shift, Map<Long, String> namesById,
                                         boolean includeStats) {
        CashShiftResponse response = new CashShiftResponse();
        response.setId(shift.getId());
        response.setCashierId(shift.getCashierId());
        response.setShiftType(shift.getShiftType().name());
        response.setOpeningAmount(nullSafe(shift.getOpeningAmount()));
        response.setOpeningTime(shift.getOpeningTime());
        response.setClosingAmount(shift.getClosingAmount());
        response.setClosingTime(shift.getClosingTime());
        response.setStatus(shift.getStatus());
        response.setNotes(shift.getNotes());
        response.setForceClosed(Boolean.TRUE.equals(shift.getForceClosed()));
        response.setForceCloseReason(shift.getForceCloseReason());
        response.setVarianceTolerance(CashVarianceUtil.DEFAULT_TOLERANCE);
        response.setCashAllowed(false);
        response.setCanJoinDrawer(false);
        response.setExpectedCashHidden(false);

        if (namesById != null) {
            response.setCashierName(namesById.getOrDefault(shift.getCashierId(), "Unknown"));
        } else {
            userRepository.findById(shift.getCashierId())
                    .ifPresent(user -> response.setCashierName(displayName(user)));
        }

        CashDrawerSession session = shift.getDrawerSession();
        if (session != null) {
            response.setDrawerSessionId(session.getId());
            if (session.getDrawer() != null) {
                response.setDrawerId(session.getDrawer().getId());
                response.setDrawerName(session.getDrawer().getName());
                response.setDrawerCode(session.getDrawer().getCode());
            }
            response.setDrawerSessionStatus(session.getStatus() != null ? session.getStatus().name() : null);
            if (session.getOpenedBy() != null) {
                response.setDrawerOwnerId(session.getOpenedBy().getId());
                response.setDrawerOwnerName(displayName(session.getOpenedBy()));
            }
            response.setBlindClose(Boolean.TRUE.equals(session.getBlindClose()));

            boolean drawerOpen = session.getStatus() == DrawerSessionStatus.OPEN;
            response.setCashAllowed(shift.getShiftType() == ShiftType.CASH_DRAWER && drawerOpen);
            response.setCanJoinDrawer(drawerOpen && cashDrawerSessionService.isSharedDrawerAllowed());

            if (includeStats) {
                // The expected amount and variance describe the whole physical
                // drawer. Only the employee who opened the drawer sees them on
                // their own shift; joining employees get their sales summary
                // without a misleading personal cash expectation.
                boolean drawerOwner = session.getOpenedBy() != null
                        && Objects.equals(session.getOpenedBy().getId(), shift.getCashierId());
                response.setDrawerCashSalesTotal(nullSafe(cashDrawerMovementRepository
                        .sumAmountBySessionAndType(session.getId(), DrawerMovementType.CASH_SALE)));
                if (drawerOwner) {
                    BigDecimal drawerExpected = cashDrawerSessionService.getExpectedCash(session.getId());
                    boolean hideExpected = drawerOpen && Boolean.TRUE.equals(session.getBlindClose());
                    response.setExpectedCashHidden(hideExpected);
                    response.setExpectedAmount(hideExpected ? null : drawerExpected);
                } else {
                    response.setExpectedCashHidden(false);
                    response.setExpectedAmount(null);
                }
            }
        } else {
            response.setCashAllowed(false);
            response.setBlindClose(cashDrawerSessionService.isBlindCloseEnabled());
            if (includeStats && shift.getShiftType() == ShiftType.CASH_DRAWER
                    && "OPEN".equals(shift.getStatus())) {
                // Pre-upgrade open shift with no drawer session.
                response.setExpectedCashHidden(true);
                response.setExpectedAmount(null);
            } else {
                response.setExpectedAmount(shift.getExpectedAmount());
                response.setVariance(shift.getVariance());
            }
        }

        if (shift.getShiftType() == ShiftType.CASH_DRAWER && session == null) {
            response.setCashAllowed("OPEN".equals(shift.getStatus()));
        }

        if (includeStats) {
            populateStats(shift, response);
        } else {
            response.setVariance(shift.getVariance());
            response.setVarianceExceedsTolerance(shift.getVariance() != null
                    && CashVarianceUtil.exceedsTolerance(shift.getVariance(), CashVarianceUtil.DEFAULT_TOLERANCE));
        }
        return response;
    }

    private void populateStats(CashShift shift, CashShiftResponse response) {
        List<Sale> sales = saleRepository.findByEmployeeShiftId(shift.getId());
        if (sales.isEmpty()) {
            sales = saleRepository.findByCashShiftId(shift.getId());
        }

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        long transactions = 0;
        long voided = 0;
        for (Sale sale : sales) {
            if (Boolean.TRUE.equals(sale.getIsVoided())) {
                voided++;
                continue;
            }
            transactions++;
            gross = gross.add(nullSafe(sale.getSubtotal()));
            discount = discount
                    .add(nullSafe(sale.getDiscountAmount()))
                    .add(nullSafe(sale.getPromotionDiscount()))
                    .add(nullSafe(sale.getPriceListDiscount()));
            net = net.add(nullSafe(sale.getTotalAmount()));
        }

        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
        BigDecimal nonCash = BigDecimal.ZERO;
        for (Object[] row : salePaymentRepository.summariseByEmployeeShift(shift.getId())) {
            PaymentMethod method = (PaymentMethod) row[0];
            BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            breakdown.put(method.name(), amount);
            if (method != PaymentMethod.CASH) {
                nonCash = nonCash.add(amount);
            }
        }

        BigDecimal shiftCashSales = nullSafe(cashDrawerMovementRepository
                .sumAmountByEmployeeShiftAndType(shift.getId(), DrawerMovementType.CASH_SALE));
        if (shiftCashSales.compareTo(BigDecimal.ZERO) == 0 && !sales.isEmpty()) {
            // Old rows have no SalePayment lines; fall back to the legacy
            // cash-sale query so historical reports keep working.
            shiftCashSales = nullSafe(saleRepository.sumNetCashSalesByShiftId(shift.getId()));
        }

        BigDecimal cashRefunds = nullSafe(cashDrawerMovementRepository
                .sumAmountByEmployeeShiftAndType(shift.getId(), DrawerMovementType.CASH_REFUND)).abs();
        BigDecimal returnsTotal = saleReturnRepository.sumTotalByEmployeeShiftId(shift.getId());
        if (returnsTotal == null) {
            returnsTotal = BigDecimal.ZERO;
        }
        if (shift.getShiftType() == ShiftType.CASH_DRAWER) {
            if (cashRefunds.compareTo(BigDecimal.ZERO) > 0) {
                returnsTotal = cashRefunds;
            }
        }

        response.setGrossSales(gross);
        response.setDiscountTotal(discount);
        response.setNetSales(net);
        response.setTransactionCount(transactions);
        response.setVoidedCount(voided);
        response.setPaymentBreakdown(breakdown);
        response.setCashSalesTotal(shiftCashSales);
        response.setNonCashSalesTotal(nonCash);
        response.setReturnsTotal(returnsTotal);

        CashDrawerSession session = shift.getDrawerSession();
        if (shift.getShiftType() == ShiftType.CASH_DRAWER && session == null) {
            BigDecimal expected = CashVarianceUtil.expectedDrawer(
                    shift.getOpeningAmount(), shiftCashSales, returnsTotal);
            response.setExpectedAmount("OPEN".equals(shift.getStatus()) ? null : expected);
            response.setExpectedCashHidden("OPEN".equals(shift.getStatus()));
        }

        response.setVarianceExceedsTolerance(response.getVariance() != null
                && CashVarianceUtil.exceedsTolerance(
                response.getVariance(), CashVarianceUtil.DEFAULT_TOLERANCE));
    }

    public boolean isManagerOrAdmin(User user) {
        if (user == null || user.getRole() == null || user.getRole().getName() == null) {
            return false;
        }
        return user.getRole().getName() == com.bms.entity.Role.RoleName.ROLE_ADMIN
                || user.getRole().getName() == com.bms.entity.Role.RoleName.ROLE_MANAGER;
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
