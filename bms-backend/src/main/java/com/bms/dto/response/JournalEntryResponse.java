package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public class JournalEntryResponse {
    private LocalDate date;
    private String reference;
    private String description;
    private String eventType;
    private String accountCode;
    private String accountName;
    private BigDecimal debit = BigDecimal.ZERO;
    private BigDecimal credit = BigDecimal.ZERO;

    public JournalEntryResponse() {
    }

    public JournalEntryResponse(LocalDate date, String reference, String description, String eventType,
                                String accountCode, String accountName,
                                BigDecimal debit, BigDecimal credit) {
        this.date = date;
        this.reference = reference;
        this.description = description;
        this.eventType = eventType;
        this.accountCode = accountCode;
        this.accountName = accountName;
        this.debit = debit != null ? debit : BigDecimal.ZERO;
        this.credit = credit != null ? credit : BigDecimal.ZERO;
    }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getAccountCode() { return accountCode; }
    public void setAccountCode(String accountCode) { this.accountCode = accountCode; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
}
