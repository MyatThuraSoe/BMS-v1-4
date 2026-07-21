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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

    public CashShiftResponse openShift(OpenShiftRequest request, Long cashierId) {
        // Check if cashier already has an open shift
        if (cashShiftRepository.existsByCashierIdAndStatus(cashierId, "OPEN")) {
            throw new BusinessException("Cashier already has an open shift");
        }

        CashShift shift = new CashShift();
        shift.setCashierId(cashierId);
        shift.setOpeningAmount(request.getOpeningAmount());
        shift.setOpeningTime(LocalDateTime.now());
        shift.setStatus("OPEN");

        CashShift savedShift = cashShiftRepository.save(shift);

        auditLogService.logAction(cashierId, "SHIFT_OPEN",
            "Shift opened with opening amount: " + request.getOpeningAmount(),
            "CashShift", savedShift.getId(), null, null);

        return convertToResponse(savedShift, null);
    }

    public CashShiftResponse getCurrentShift(Long cashierId) {
        return cashShiftRepository.findByCashierIdAndStatus(cashierId, "OPEN")
            .map(shift -> convertToResponse(shift, null))
            .orElse(null);
    }

    public CashShiftResponse closeShift(Long shiftId, CloseShiftRequest request, Long userId) {
        CashShift shift = cashShiftRepository.findById(shiftId)
            .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (!"OPEN".equals(shift.getStatus())) {
            throw new BusinessException("Shift is already closed");
        }

        BigDecimal cashSalesTotal = saleRepository.sumCashSalesByShiftId(shiftId);
        BigDecimal expectedAmount = shift.getOpeningAmount().add(cashSalesTotal);
        BigDecimal variance = request.getClosingAmount().subtract(expectedAmount);

        shift.setClosingAmount(request.getClosingAmount());
        shift.setClosingTime(LocalDateTime.now());
        shift.setExpectedAmount(expectedAmount);
        shift.setVariance(variance);
        shift.setStatus("CLOSED");
        shift.setNotes(request.getNotes());

        CashShift savedShift = cashShiftRepository.save(shift);

        auditLogService.logAction(userId, "SHIFT_CLOSE",
            "Shift closed with variance: " + variance,
            "CashShift", shiftId, null, null);

        return convertToResponse(savedShift, null);
    }

    public Page<CashShiftResponse> getShiftHistory(Long cashierId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return cashShiftRepository.findByFilters(cashierId, startDate, endDate, pageable)
            .map(shift -> convertToResponse(shift, null));
    }

    public CashShiftResponse getShiftById(Long shiftId, Long requestingUserId) {
        CashShift shift = cashShiftRepository.findById(shiftId)
            .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        User requestingUser = userRepository.findById(requestingUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Only allow admin/manager or the shift's own cashier to view details
        boolean isAdminOrManager = requestingUser.getRoles().stream()
            .anyMatch(r -> "ROLE_ADMIN".equals(r.getName()) || "ROLE_MANAGER".equals(r.getName()));
        
        if (!isAdminOrManager && !shift.getCashierId().equals(requestingUserId)) {
            throw new BusinessException("Not authorized to view this shift");
        }

        return convertToResponse(shift, requestingUserId);
    }

    private CashShiftResponse convertToResponse(CashShift shift, Long requestingUserId) {
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

        // Get cashier name
        userRepository.findById(shift.getCashierId()).ifPresent(cashier -> 
            response.setCashierName(cashier.getUsername()));

        // Include sales if viewing a specific shift detail
        if (requestingUserId != null) {
            List<Sale> sales = saleRepository.findAll().stream()
                .filter(s -> s.getCashShift() != null && s.getCashShift().getId().equals(shift.getId()))
                .collect(Collectors.toList());
            response.setSales(sales.stream()
                .map(this::convertSaleToResponse)
                .collect(Collectors.toList()));
        }

        return response;
    }

    private com.bms.dto.response.SaleResponse convertSaleToResponse(Sale sale) {
        com.bms.dto.response.SaleResponse response = new com.bms.dto.response.SaleResponse();
        response.setId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        response.setTotalAmount(sale.getTotalAmount());
        response.setPaymentMethod(sale.getPaymentMethod().name());
        response.setSaleDate(sale.getSaleDate());
        response.setIsVoided(sale.getIsVoided());
        return response;
    }
}
