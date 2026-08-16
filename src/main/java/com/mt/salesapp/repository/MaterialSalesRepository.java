package com.mt.salesapp.repository;

import com.mt.salesapp.model.MaterialSalesEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface MaterialSalesRepository extends JpaRepository<MaterialSalesEntry, Long> {
    List<MaterialSalesEntry> findBySoldDateBetween(LocalDate start, LocalDate end);

    @Query("SELECT COALESCE(SUM(m.totalAmount), 0) FROM MaterialSalesEntry m")
    Double getTotalRevenue();

    @Query("SELECT COALESCE(SUM(m.profit), 0) FROM MaterialSalesEntry m")
    Double getTotalProfit();

    List<MaterialSalesEntry> findBySoldDate(LocalDate soldDate);
}