package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "mt_customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String customerName;
    private String phoneNumber;
    @Column(length = 500)
    private String address;
    private Double outstandingBalance = 0.0;
}