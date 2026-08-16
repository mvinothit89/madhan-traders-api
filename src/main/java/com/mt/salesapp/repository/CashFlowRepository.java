package com.mt.salesapp.repository;

import com.mt.salesapp.model.CashFlowEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CashFlowRepository extends JpaRepository<CashFlowEntry, Long> {
    List<CashFlowEntry> findByEntryDateBetween(LocalDate start, LocalDate end);
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashFlowEntry c WHERE c.transactionType = :type")
    Double getSumByType(@Param("type") String type);
}