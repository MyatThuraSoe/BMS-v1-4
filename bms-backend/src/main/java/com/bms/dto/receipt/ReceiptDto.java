package com.bms.dto.receipt;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptDto {
    private String invoiceNumber;
    private LocalDateTime saleDate;
    private String cashierName;
    private String customerName;
    private List<ReceiptItemDto> items;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private BigDecimal amountPaid;
    private BigDecimal changeGiven;
    private String paymentMethod;
}
