package com.bms.dto.receipt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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


    // No-Args Constructor
    public ReceiptDto() {
    }


    // All-Args Constructor
    public ReceiptDto(
            String invoiceNumber,
            LocalDateTime saleDate,
            String cashierName,
            String customerName,
            List<ReceiptItemDto> items,
            BigDecimal subtotal,
            BigDecimal taxAmount,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            BigDecimal amountPaid,
            BigDecimal changeGiven,
            String paymentMethod
    ) {
        this.invoiceNumber = invoiceNumber;
        this.saleDate = saleDate;
        this.cashierName = cashierName;
        this.customerName = customerName;
        this.items = items;
        this.subtotal = subtotal;
        this.taxAmount = taxAmount;
        this.discountAmount = discountAmount;
        this.totalAmount = totalAmount;
        this.amountPaid = amountPaid;
        this.changeGiven = changeGiven;
        this.paymentMethod = paymentMethod;
    }


    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }


    public LocalDateTime getSaleDate() {
        return saleDate;
    }

    public void setSaleDate(LocalDateTime saleDate) {
        this.saleDate = saleDate;
    }


    public String getCashierName() {
        return cashierName;
    }

    public void setCashierName(String cashierName) {
        this.cashierName = cashierName;
    }


    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }


    public List<ReceiptItemDto> getItems() {
        return items;
    }

    public void setItems(List<ReceiptItemDto> items) {
        this.items = items;
    }


    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }


    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }


    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }


    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }


    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }


    public BigDecimal getChangeGiven() {
        return changeGiven;
    }

    public void setChangeGiven(BigDecimal changeGiven) {
        this.changeGiven = changeGiven;
    }


    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}