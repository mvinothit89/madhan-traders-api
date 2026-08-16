package com.mt.salesapp.service;

import com.mt.salesapp.model.DailySales;
import com.mt.salesapp.repository.DailySalesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class DailySalesService {

    @Autowired
    private DailySalesRepository repository;

    public List<DailySales> getAllSales() {
        return repository.findAll();
    }

    public DailySales getSalesByDate(LocalDate date) {
        return repository.findBySaleDate(date).orElse(null);
    }

    @Transactional
    public DailySales saveOrUpdateSales(DailySales salesEntry) {
        // Check if an entry already exists for this date to update it instead of creating a duplicate
        return repository.findBySaleDate(salesEntry.getSaleDate())
                .map(existing -> {
                    existing.setWaterCash(salesEntry.getWaterCash());
                    existing.setWaterGpay(salesEntry.getWaterGpay());
                    existing.setMaterialCash(salesEntry.getMaterialCash());
                    existing.setMaterialGpay(salesEntry.getMaterialGpay());
                    existing.setExpenses(salesEntry.getExpenses());
                    existing.setTotalAmount(salesEntry.getTotalAmount());
                    existing.setRemarks(salesEntry.getRemarks());
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(salesEntry));
    }
}