package com.mt.salesapp.controller;

import com.mt.salesapp.model.DailyExpenseSum;
import com.mt.salesapp.model.ExpenseLedger;
import com.mt.salesapp.service.ExpenseLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/expenses")
@CrossOrigin(origins = "*")
public class ExpenseLedgerController {

    @Autowired
    private ExpenseLedgerService service;

    // 1. List All / CRUD: Get All
    @GetMapping
    public List<ExpenseLedger> getAllExpenses() {
        return service.getAllExpenses();
    }

    // CRUD: Get By ID
    @GetMapping("/{id}")
    public ResponseEntity<ExpenseLedger> getExpenseById(@PathVariable Long id) {
        return service.getExpenseById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // CRUD: Create
    @PostMapping
    public ExpenseLedger createExpense(@RequestBody ExpenseLedger expense) {
        return service.saveExpense(expense);
    }

    // CRUD: Update
    @PutMapping("/{id}")
    public ResponseEntity<ExpenseLedger> updateExpense(@PathVariable Long id, @RequestBody ExpenseLedger expenseDetails) {
        try {
            ExpenseLedger updated = service.updateExpense(id, expenseDetails);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // CRUD: Delete
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long id) {
        service.deleteExpense(id);
        return ResponseEntity.noContent().build();
    }

    // 2. Search by Date Range API
    // Example: GET /api/expenses/search?startDate=2026-08-01&endDate=2026-08-31
    @GetMapping("/search")
    public List<ExpenseLedger> getExpensesByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return service.getExpensesByDateRange(startDate, endDate);
    }

    // 3. Sum of Expenses per Day API Service
    // Example: GET /api/expenses/sum-per-day?startDate=2026-08-01&endDate=2026-08-31
    @GetMapping("/sum-per-day")
    public List<DailyExpenseSum> getDailyExpenseSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return service.getDailyExpenseSummary(startDate, endDate);
    }
}