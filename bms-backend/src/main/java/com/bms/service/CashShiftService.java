package com.bms.service;

import com.bms.dto.request.CloseShiftRequest;
import com.bms.dto.request.OpenShiftRequest;
import com.bms.dto.response.CashShiftResponse;
import com.bms.entity.CashShift;
import com.bms.entity.Sale;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import com.bms.util.CashVarianceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class CashShiftService {

    @Autowired
    private CashShiftRepository cashShiftRepository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogService auditLogService;

    public CashShiftResponse openShift(Long cashierId, OpenShiftRequest request) {
        // Fix A4: Pessimistic lock prevents concurrent "open shift" requests for the same user
        userRepository.findByIdForUpdate(cashierId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (cashShiftRepository.existsByCashierIdAndStatus(cashierId, "OPEN")) {
            throw new BusinessException("You already have an open shift. Close it before starting a new one.");
        }
        CashShift shift = new CashShift();
        shift.setCashierId(cashierId);
        shift.setOpeningAmount(request.getOpeningAmount());
        shift.setOpeningTime(LocalDateTime.now());
        shift.setStatus("OPEN");
        CashShift saved = cashShiftRepository.save(shift);

        // The declared float is the baseline every later variance is measured
        // against, so record it at open time. Closing logs the delta but would
        // not capture where the delta started.
        auditLogService.logAction(cashierId, "SHIFT_OPEN",
                "Shift opened with " + request.getOpeningAmount() + " in the drawer",
                "CashShift", saved.getId(), null, null);

        return convertToResponse(saved);
    }

    public CashShiftResponse getCurrentShift(Long cashierId) {
        CashShift shift = cashShiftRepository.findByCashierIdAndStatus(cashierId, "OPEN").orElse(null);
        if (shift == null) return null;
        CashShiftResponse response = convertToResponse(shift);
        BigDecimal cashSalesTotal = saleRepository.sumNetCashSalesByShiftId(shift.getId());
        response.setCashSalesTotal(cashSalesTotal != null ? cashSalesTotal : BigDecimal.ZERO);
        // Refunds that physically happened while this shift was open. Keyed on
        // the refund date, not the originating sale's shift, so refunding an
        // older sale still shows up as cash leaving this drawer.
        BigDecimal returnsTotal = saleRepository.sumRefundsBetween(
                shift.getOpeningTime(), LocalDateTime.now());
        response.setReturnsTotal(returnsTotal != null ? returnsTotal : BigDecimal.ZERO);
        response.setExpectedAmount(CashVarianceUtil.expectedDrawer(
                shift.getOpeningAmount(),
                response.getCashSalesTotal(),
                response.getReturnsTotal()));
        return response;
    }

    public CashShiftResponse closeShift(Long shiftId, CloseShiftRequest request, Long userId) {
        // PESSIMISTIC_WRITE lock: only one concurrent close succeeds.
        // The second caller blocks here, then sees status != OPEN and is rejected.
        CashShift shift = cashShiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        if (!"OPEN".equals(shift.getStatus())) {
            throw new BusinessException("Shift is already closed");
        }

        BigDecimal cashSalesTotal = saleRepository.sumNetCashSalesByShiftId(shiftId);
        if (cashSalesTotal == null) cashSalesTotal = BigDecimal.ZERO;

        // Stamp the close time first and use it as the window bound, so the
        // recorded closing time is the exact edge of what was counted. Taking
        // the bound from a later now() would silently drop a refund that landed
        // in between.
        LocalDateTime closingTime = LocalDateTime.now();

        // Same window rule as the live view: cash handed back before the close
        // timestamp counts against this shift even if the sale was from earlier.
        BigDecimal returnsTotal = saleRepository.sumRefundsBetween(
                shift.getOpeningTime(), closingTime);
        if (returnsTotal == null) returnsTotal = BigDecimal.ZERO;

        BigDecimal expectedAmount = CashVarianceUtil.expectedDrawer(
                shift.getOpeningAmount(), cashSalesTotal, returnsTotal);
        BigDecimal variance = CashVarianceUtil.variance(request.getClosingAmount(), expectedAmount);

        shift.setClosingAmount(request.getClosingAmount());
        shift.setClosingTime(closingTime);
        shift.setExpectedAmount(expectedAmount);
        shift.setVariance(variance);
        shift.setStatus("CLOSED");
        shift.setNotes(request.getNotes());

        CashShift saved = cashShiftRepository.save(shift);

        auditLogService.logAction(userId, "SHIFT_CLOSE",
                "Shift closed. Expected: " + expectedAmount + ", Actual: " + request.getClosingAmount() + ", Variance: " + variance
                        + CashVarianceUtil.describeVariance(variance, expectedAmount),
                "CashShift", shiftId, null, null);

        return convertToResponse(saved);
    }

    public CashShiftResponse getShiftById(Long shiftId) {
        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        CashShiftResponse response = convertToResponse(shift);
        List<Sale> sales = saleRepository.findByCashShiftId(shiftId);
        List<CashShiftResponse.SaleReference> saleRefs = sales.stream().map(s -> {
            CashShiftResponse.SaleReference ref = new CashShiftResponse.SaleReference();
            ref.setId(s.getId());
            ref.setInvoiceNumber(s.getInvoiceNumber());
            ref.setTotalAmount(s.getTotalAmount());
            ref.setSaleDate(s.getSaleDate());
            return ref;
        }).collect(Collectors.toList());
        response.setSales(saleRefs);

        BigDecimal cashSalesTotal = saleRepository.sumNetCashSalesByShiftId(shiftId);
        response.setCashSalesTotal(cashSalesTotal != null ? cashSalesTotal : BigDecimal.ZERO);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<CashShiftResponse> getShiftHistory(Long cashierId, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDateTime.now();

        Page<CashShift> shifts;
        // Fix A2 & A3: Use methods that include OPEN shifts and correctly respect the date range
        if (cashierId != null) {
            shifts = cashShiftRepository.findByCashierIdsAndDateRange(java.util.List.of(cashierId), startDateTime, endDateTime, pageable);
        } else {
            shifts = cashShiftRepository.findByDateRange(startDateTime, endDateTime, pageable);
        }

        // Fix B1: Batch-load users to prevent N+1 query problem
        Set<Long> cashierIds = shifts.getContent().stream()
                .map(CashShift::getCashierId)
                .collect(Collectors.toSet());
        
        Map<Long, String> namesById = userRepository.findAllById(cashierIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getFirstName() + " " + u.getLastName()));

        return shifts.map(shift -> convertToResponse(shift, namesById));
    }

    // Base mapper to avoid code duplication
    private CashShiftResponse buildBaseResponse(CashShift shift) {
        CashShiftResponse response = new CashShiftResponse();
        response.setId(shift.getId());
        response.setCashierId(shift.getCashierId());
        response.setOpeningAmount(shift.getOpeningAmount());
        response.setOpeningTime(shift.getOpeningTime());
        response.setClosingAmount(shift.getClosingAmount());
        response.setClosingTime(shift.getClosingTime());
        response.setExpectedAmount(shift.getExpectedAmount());
        response.setVariance(shift.getVariance());
        response.setStatus(shift.getStatus());
        response.setNotes(shift.getNotes());
        // Surfaced so the close screen and history read the same threshold the
        // backend applies instead of each hardcoding their own.
        response.setVarianceTolerance(CashVarianceUtil.DEFAULT_TOLERANCE);
        response.setVarianceExceedsTolerance(shift.getVariance() != null
                && CashVarianceUtil.exceedsTolerance(shift.getVariance(), CashVarianceUtil.DEFAULT_TOLERANCE));
        return response;
    }

    // For single shift lookups (no N+1 concern)
    private CashShiftResponse convertToResponse(CashShift shift) {
        CashShiftResponse response = buildBaseResponse(shift);
        userRepository.findById(shift.getCashierId()).ifPresent(user -> {
            response.setCashierName(user.getFirstName() + " " + user.getLastName());
        });
        return response;
    }

    // For paginated list lookups (Fix B1)
    private CashShiftResponse convertToResponse(CashShift shift, Map<Long, String> namesById) {
        CashShiftResponse response = buildBaseResponse(shift);
        response.setCashierName(namesById.getOrDefault(shift.getCashierId(), "Unknown"));
        return response;
    }
}