package com.mt.salesapp.service;

import com.mt.salesapp.model.*;
import com.mt.salesapp.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BusinessService {
    @Autowired public ProductRepository productRepo;
    @Autowired public WaterSalesRepository waterRepo;
    @Autowired public MaterialSalesRepository materialRepo;
    @Autowired public CashFlowRepository cashFlowRepo;
    @Autowired public CashCollectedRepository cashCollectedRepo;
    @Autowired public CustomerRepository customerRepo;
    @Autowired public PaymentRepository paymentRepo;

    public Map<String, Object> getDashboardSummary(LocalDate start, LocalDate end) {
        List<WaterSalesEntry> waters = (start != null && end != null) ? waterRepo.findBySoldDateBetween(start, end) : waterRepo.findAll();
        List<MaterialSalesEntry> materials = (start != null && end != null) ? materialRepo.findBySoldDateBetween(start, end) : materialRepo.findAll();
        List<CashFlowEntry> flows = (start != null && end != null) ? cashFlowRepo.findByEntryDateBetween(start, end) : cashFlowRepo.findAll();

        double totalWaterRevenue = waters.stream().mapToDouble(w -> w.getTotalAmount() != null ? w.getTotalAmount() : 0.0).sum();
        double totalWaterProfit = waters.stream().mapToDouble(w -> w.getProfit() != null ? w.getProfit() : 0.0).sum();
        double totalMaterialRevenue = materials.stream().mapToDouble(m -> m.getTotalAmount() != null ? m.getTotalAmount() : 0.0).sum();
        double totalMaterialProfit = materials.stream().mapToDouble(m -> m.getProfit() != null ? m.getProfit() : 0.0).sum();

        double totalInflow = flows.stream().filter(f -> "INFLOW".equalsIgnoreCase(f.getTransactionType())).mapToDouble(CashFlowEntry::getAmount).sum();
        double totalOutflow = flows.stream().filter(f -> "OUTFLOW".equalsIgnoreCase(f.getTransactionType())).mapToDouble(CashFlowEntry::getAmount).sum();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalWaterRevenue", totalWaterRevenue);
        summary.put("totalWaterProfit", totalWaterProfit);
        summary.put("totalMaterialRevenue", totalMaterialRevenue);
        summary.put("totalMaterialProfit", totalMaterialProfit);
        summary.put("netRevenue", totalWaterRevenue + totalMaterialRevenue);
        summary.put("netProfit", totalWaterProfit + totalMaterialProfit);
        summary.put("totalInflow", totalInflow);
        summary.put("totalOutflow", totalOutflow);
        summary.put("netCashFlow", totalInflow - totalOutflow);
        return summary;
    }
}