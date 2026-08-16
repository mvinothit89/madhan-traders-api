package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_water_sales")
public class WaterSalesEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate soldDate;

    private String item = "Aqua Fresh Water";

    private Integer totalCansSold;

    private Double cashAmount;

    private Double gpayAmount;

    private Double totalAmount;

    private Double profit;
}