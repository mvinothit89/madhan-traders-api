package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_expense_ledger")
public class ExpenseLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private LocalDate expenseDate;
    
    private String expenseCategory; 
    
    private Double amount;
    
    @Column(length = 150)
    private String paidTo;
    
    private String approvedBy = "MT-Admin";
    
    @Column(length = 500)
    private String notes;
}