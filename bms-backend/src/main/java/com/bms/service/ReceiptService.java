package com.bms.service;

import com.bms.dto.receipt.ReceiptDto;
import com.bms.dto.receipt.ReceiptItemDto;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.User;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReceiptService {

    private final SaleRepository saleRepository;
    private final UserRepository userRepository;
    public ReceiptService(SaleRepository saleRepository, UserRepository userRepository) {
        this.saleRepository = saleRepository;
        this.userRepository = userRepository;
    }

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

        String customerName = "Walk-in Customer";
        if (sale.getCustomer() != null) {
            customerName = sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName();
        }

        List<ReceiptItemDto> items = sale.getItems().stream()
                .map(this::toReceiptItemDto)
                .toList();

        return new ReceiptDto(
                sale.getInvoiceNumber(),
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
                sale.getPaymentMethod().name()
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

        String customerName = "Walk-in Customer";
        if (sale.getCustomer() != null) {
            customerName = sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName();
        }

        List<ReceiptItemDto> items = sale.getItems().stream()
                .map(this::toReceiptItemDto)
                .toList();

        return new ReceiptDto(
                sale.getInvoiceNumber(),
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
                sale.getPaymentMethod().name()
        );
    }

    private ReceiptItemDto toReceiptItemDto(SaleItem item) {
        return new ReceiptItemDto(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getSku(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getTotalPrice()
        );
    }
}
