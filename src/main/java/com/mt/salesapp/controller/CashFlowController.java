package com.mt.salesapp.controller;

import com.mt.salesapp.model.CashFlowEntry;
import com.mt.salesapp.service.CashFlowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cash-flow")
public class CashFlowController {

    private final CashFlowService cashFlowService;

    public CashFlowController(CashFlowService cashFlowService) {
        this.cashFlowService = cashFlowService;
    }

    @GetMapping
    public ResponseEntity<List<CashFlowEntry>> getAllEntries() {
        return ResponseEntity.ok(cashFlowService.getAllOrderedByDateDesc());
    }

    @PostMapping
    public ResponseEntity<CashFlowEntry> createEntry(@RequestBody CashFlowEntry CashFlowEntry) {
        return ResponseEntity.ok(cashFlowService.save(CashFlowEntry));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CashFlowEntry> updateEntry(@PathVariable Long id, @RequestBody CashFlowEntry CashFlowEntry) {
        CashFlowEntry.setId(id);
        return ResponseEntity.ok(cashFlowService.save(CashFlowEntry));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long id) {
        cashFlowService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}