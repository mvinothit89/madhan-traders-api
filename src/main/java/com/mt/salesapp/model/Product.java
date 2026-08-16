package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mt_products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String itemName;

    @Column(length = 1000)
    private String description;

    private Integer quantity;

    private Double unitCost;

    private Double sellingPrice;

    private LocalDate createDate = LocalDate.now();

    private LocalDateTime createTimestamp = LocalDateTime.now();
}