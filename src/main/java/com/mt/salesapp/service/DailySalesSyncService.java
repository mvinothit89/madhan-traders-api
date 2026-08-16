package com.mt.salesapp.service;

import com.mt.salesapp.model.DailySales;
import com.mt.salesapp.repository.DailySalesRepository;
import com.mt.salesapp.repository.WaterSalesRepository;
import com.mt.salesapp.repository.MaterialSalesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DailySalesSyncService {

    @Autowired
    private DailySalesRepository dailySalesRepository;

    @Autowired
    private WaterSalesRepository waterSalesRepository;

    @Autowired
    private MaterialSalesRepository materialSalesRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public int syncDailySalesFromSourceTables() {
        // 1. Gather all unique dates from water and material sales
        Set<LocalDate> allDates = new HashSet<>();
        
        waterSalesRepository.findAll().forEach(w -> {
            if (w.getSoldDate() != null) allDates.add(w.getSoldDate());
        });
        
        materialSalesRepository.findAll().forEach(m -> {
            if (m.getSoldDate() != null) allDates.add(m.getSoldDate());
        });

        int processedCount = 0;

        // 2. Aggregate and Upsert for each date
        for (LocalDate date : allDates) {
            // Calculate Water Totals for this date
            double waterCash = waterSalesRepository.findBySoldDate(date).stream()
                    .mapToDouble(w -> w.getCashAmount() != null ? w.getCashAmount() : 0.0)
                    .sum();
            
            double waterGpay = waterSalesRepository.findBySoldDate(date).stream()
                    .mapToDouble(w -> w.getGpayAmount() != null ? w.getGpayAmount() : 0.0)
                    .sum();

            // Calculate Material Totals for this date
            // Note: If your material sales table tracks cash/gpay separately, adjust the fields accordingly. 
            // Here we assume material sales total amounts can be mapped or separated by payment mode.
            double materialCash = materialSalesRepository.findBySoldDate(date).stream()
                    .mapToDouble(m -> m.getCashAmount() != null ? m.getCashAmount() : 0.0) // Adjust if cash/gpay fields exist
                    .sum();

            double materialGpay = materialSalesRepository.findBySoldDate(date).stream()
                    .mapToDouble(m -> m.getGpayAmount() != null ? m.getGpayAmount() : 0.0) // Adjust if cash/gpay fields exist
                    .sum();
            
            // Fetch or create DailySales record for the date
            DailySales dailySales = dailySalesRepository.findBySaleDate(date)
                    .orElse(new DailySales());

            dailySales.setSaleDate(date);
            dailySales.setWaterCash(waterCash);
            dailySales.setWaterGpay(waterGpay);
            dailySales.setMaterialCash(materialCash);
            dailySales.setMaterialGpay(materialGpay);
            dailySales.setTotalAmount(waterCash+waterGpay+materialCash+materialGpay);
            dailySalesRepository.save(dailySales);
            processedCount++;
        }

        return processedCount;
    }
}