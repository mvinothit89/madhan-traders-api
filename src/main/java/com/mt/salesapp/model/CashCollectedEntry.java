package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_cash_collected")
public class CashCollectedEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate collectionDate;
    private String collectorName;
    private String customerName;
    private Double collectedAmount;
    private String paymentMode; // CASH, GPAY, BANK_TRANSFER
}