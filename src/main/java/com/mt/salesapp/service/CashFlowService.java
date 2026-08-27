package com.mt.salesapp.service;

import com.mt.salesapp.model.CashFlowEntry;
import com.mt.salesapp.repository.CashFlowRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CashFlowService {

    private final CashFlowRepository cashFlowRepository;

    public CashFlowService(CashFlowRepository cashFlowRepository) {
        this.cashFlowRepository = cashFlowRepository;
    }

    public List<CashFlowEntry> getAllOrderedByDateDesc() {
        return cashFlowRepository.findAllByOrderByEntryDateDesc();
    }

    public CashFlowEntry save(CashFlowEntry CashFlowEntry) {
        if (!"INFLOW".equals(CashFlowEntry.getTransactionType()) && !"OUTFLOW".equals(CashFlowEntry.getTransactionType())) {
            throw new IllegalArgumentException("Transaction type must be INFLOW or OUTFLOW");
        }
        return cashFlowRepository.save(CashFlowEntry);
    }

    public void deleteById(Long id) {
        cashFlowRepository.deleteById(id);
    }
}