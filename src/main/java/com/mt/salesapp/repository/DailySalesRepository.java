package com.mt.salesapp.repository;

import com.mt.salesapp.model.DailySales;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailySalesRepository extends JpaRepository<DailySales, Long> {
    Optional<DailySales> findBySaleDate(LocalDate saleDate);
}