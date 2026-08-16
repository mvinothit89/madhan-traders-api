package com.mt.salesapp.repository;

import com.mt.salesapp.model.CashCollectedEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CashCollectedRepository extends JpaRepository<CashCollectedEntry, Long> {
    List<CashCollectedEntry> findByCollectionDateBetween(LocalDate start, LocalDate end);
}