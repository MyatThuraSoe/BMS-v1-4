package com.bms.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(
        name = "invoice_sequences",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "sequence_date")
        }
)
public class InvoiceSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate sequenceDate;

    private Integer lastNumber;


    public Long getId() {
        return id;
    }

    public LocalDate getSequenceDate() {
        return sequenceDate;
    }

    public void setSequenceDate(LocalDate sequenceDate) {
        this.sequenceDate = sequenceDate;
    }

    public Integer getLastNumber() {
        return lastNumber;
    }

    public void setLastNumber(Integer lastNumber) {
        this.lastNumber = lastNumber;
    }
}