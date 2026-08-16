package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "mt_material_sales")
public class MaterialSalesEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate soldDate;

    private String itemName;

    private Integer quantity;

    private Double unitPrice;

    private Double cashAmount;

    private Double gpayAmount;

    private Double totalAmount;

    private Double profit;
}