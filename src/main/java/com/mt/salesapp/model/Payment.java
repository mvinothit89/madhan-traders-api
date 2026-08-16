package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate paymentDate;

    @Column(name = "customer_id")
    private Long customerId;

    private String customerName;

    private Double amount;

    private String paymentMode;

    private String referenceNo;
}