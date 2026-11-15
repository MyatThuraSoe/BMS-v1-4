package com.bms.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "customer_sequences",
        uniqueConstraints = @UniqueConstraint(columnNames = "sequence_date"))
public class CustomerSequence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_date", nullable = false)
    private LocalDate sequenceDate;

    @Column(name = "last_number", nullable = false)
    private Integer lastNumber;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getSequenceDate() { return sequenceDate; }
    public void setSequenceDate(LocalDate sequenceDate) { this.sequenceDate = sequenceDate; }

    public Integer getLastNumber() { return lastNumber; }
    public void setLastNumber(Integer lastNumber) { this.lastNumber = lastNumber; }
}
