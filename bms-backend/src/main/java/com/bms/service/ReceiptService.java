package com.bms.service;

import com.bms.dto.receipt.ArPaymentReceiptDto;
import com.bms.dto.receipt.ReceiptDto;
import com.bms.dto.receipt.ReceiptItemDto;
import com.bms.entity.ArPayment;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.User;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;



import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReceiptService {


    private final SaleRepository saleRepository;


    private final UserRepository userRepository;

    @Autowired
    private ArPaymentRepository arPaymentRepository;

//    // ✅ ADD THIS CONSTRUCTOR to make the red lines disappear
//    public ReceiptService(SaleRepository saleRepository, UserRepository userRepository) {
//        this.saleRepository = saleRepository;
//        this.userRepository = userRepository;
//    }


    @Transactional(readOnly = true)
    public ReceiptDto getReceiptByInvoiceNumber(String invoiceNumber) {
        Sale sale = saleRepository.findByInvoiceNumber(invoiceNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found with invoice number: " + invoiceNumber));

        if (sale.getIsVoided()) {
            throw new IllegalStateException("Cannot generate receipt for voided sale: " + invoiceNumber);
        }

        String cashierName = "Unknown";
        if (sale.getCashierId() != null) {
            User cashier = userRepository.findById(sale.getCashierId()).orElse(null);
            if (cashier != null) {
                cashierName = cashier.getUsername();
            }
        }

        String customerName = resolveCustomerName(sale);

        List<ReceiptItemDto> items = sale.getItems().stream()
                .map(this::toReceiptItemDto)
                .toList();

        return new ReceiptDto(
                sale.getInvoiceNumber(),
                sale.getId(),
                sale.getSaleDate(),
                cashierName,
                customerName,
                items,
                sale.getSubtotal(),
                sale.getTaxAmount(),
                sale.getDiscountAmount(),
                sale.getTotalAmount(),
                sale.getAmountPaid(),
                sale.getChangeGiven(),
                sale.getPaymentMethod().name(),
                sale.getSaleType() != null ? sale.getSaleType().name() : "CASH",
                sale.getPaymentStatus() != null ? sale.getPaymentStatus().name() : "PAID",
                sale.getDueDate(),
                sale.getTotalAmount().subtract(
                        sale.getAmountPaid() != null ? sale.getAmountPaid() : java.math.BigDecimal.ZERO)
        );
    }

    @Transactional(readOnly = true)
    public ReceiptDto getReceiptById(Long id) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found with id: " + id));

        if (sale.getIsVoided()) {
            throw new IllegalStateException("Cannot generate receipt for voided sale");
        }

        String cashierName = "Unknown";
        if (sale.getCashierId() != null) {
            User cashier = userRepository.findById(sale.getCashierId()).orElse(null);
            if (cashier != null) {
                cashierName = cashier.getUsername();
            }
        }

        String customerName = resolveCustomerName(sale);

        List<ReceiptItemDto> items = sale.getItems().stream()
                .map(this::toReceiptItemDto)
                .toList();

        return new ReceiptDto(
                sale.getInvoiceNumber(),
                sale.getId(),
                sale.getSaleDate(),
                cashierName,
                customerName,
                items,
                sale.getSubtotal(),
                sale.getTaxAmount(),
                sale.getDiscountAmount(),
                sale.getTotalAmount(),
                sale.getAmountPaid(),
                sale.getChangeGiven(),
                sale.getPaymentMethod().name(),
                sale.getSaleType() != null ? sale.getSaleType().name() : "CASH",
                sale.getPaymentStatus() != null ? sale.getPaymentStatus().name() : "PAID",
                sale.getDueDate(),
                sale.getTotalAmount().subtract(
                        sale.getAmountPaid() != null ? sale.getAmountPaid() : java.math.BigDecimal.ZERO)
        );
    }

    private String resolveCustomerName(Sale sale) {
        if (sale.getCustomer() != null) {
            String displayName = sale.getCustomerDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName();
        }
        String displayName = sale.getCustomerDisplayName();
        if (displayName == null || displayName.isBlank()
                || "Walk-in".equalsIgnoreCase(displayName.trim())
                || "Walk-in Customer".equalsIgnoreCase(displayName.trim())) {
            return null;
        }
        return displayName;
    }

    private ReceiptItemDto toReceiptItemDto(SaleItem item) {
        return new ReceiptItemDto(
                item.getProduct().getId(),
                item.getId(),
                item.getProduct().getName(),
                item.getProduct().getSku(),
                item.getProduct().getUnit(),
                item.getQuantity(),
                item.getQuantityRefunded(),
                item.getUnitPrice(),
                item.getTotalPrice()
        );
    }

    /**
     * Builds the data for an AR payment receipt (a payment made against a
     * credit invoice). Used by the "print payment receipt" action.
     */
    @Transactional(readOnly = true)
    public ArPaymentReceiptDto getArPaymentReceipt(Long paymentId) {
        ArPayment payment = arPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        Sale sale = payment.getInvoice();

        ArPaymentReceiptDto dto = new ArPaymentReceiptDto();
        dto.setPaymentId(payment.getId());
        dto.setInvoiceNumber(sale.getInvoiceNumber());
        dto.setCustomerName(resolveCustomerName(sale));
        dto.setAmount(payment.getAmount());
        dto.setPaymentDate(payment.getPaymentDate());
        if (payment.getRecordedBy() != null) {
            User user = payment.getRecordedBy();
            dto.setRecordedByName((user.getFirstName() + " " + user.getLastName()).trim());
            if (dto.getRecordedByName().isBlank()) {
                dto.setRecordedByName(user.getUsername());
            }
        }
        dto.setNotes(payment.getNotes());

        BigDecimal paid = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal balanceAfter = sale.getTotalAmount().subtract(paid);
        dto.setBalanceAfter(balanceAfter.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : balanceAfter);
        return dto;
    }
}
