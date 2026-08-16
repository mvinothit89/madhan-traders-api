package com.mt.salesapp.repository;

import com.mt.salesapp.model.WaterSalesEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface WaterSalesRepository extends JpaRepository<WaterSalesEntry, Long> {
    List<WaterSalesEntry> findBySoldDateBetween(LocalDate start, LocalDate end);

    @Query("SELECT COALESCE(SUM(w.totalAmount), 0) FROM WaterSalesEntry w")
    Double getTotalRevenue();

    @Query("SELECT COALESCE(SUM(w.profit), 0) FROM WaterSalesEntry w")
    Double getTotalProfit();

    List<WaterSalesEntry> findBySoldDate(LocalDate soldDate);
}