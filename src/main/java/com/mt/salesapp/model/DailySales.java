package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_daily_sales")
public class DailySales {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_date", nullable = false, unique = true)
    private LocalDate saleDate;

    @Column(name = "water_cash")
    private Double waterCash = 0.0;

    @Column(name = "water_gpay")
    private Double waterGpay = 0.0;

    @Column(name = "material_cash")
    private Double materialCash = 0.0;

    @Column(name = "material_gpay")
    private Double materialGpay = 0.0;

    @Column(name = "expenses")
    private Double expenses = 0.0;

    @Column(name = "total_amount")
    private Double totalAmount = 0.0;

    @Column(name = "remarks", length = 500)
    private String remarks;
}