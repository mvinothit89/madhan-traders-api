package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_cash_flow")
public class CashFlowEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate entryDate;
    private String transactionType; // INFLOW / OUTFLOW
    private String category; // WATER_SALES, MATERIAL_SALES, EXPENSE, SUPPLIER_BUYING
    private Double amount;
    @Column(length = 500)
    private String description;
}
