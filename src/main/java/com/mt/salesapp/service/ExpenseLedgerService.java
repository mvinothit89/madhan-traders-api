package com.mt.salesapp.service;

import com.mt.salesapp.model.DailyExpenseSum;
import com.mt.salesapp.model.ExpenseLedger;
import com.mt.salesapp.repository.ExpenseLedgerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class ExpenseLedgerService {

    @Autowired
    private ExpenseLedgerRepository repository;

    public List<ExpenseLedger> getAllExpenses() {
        return repository.findAll();
    }

    public Optional<ExpenseLedger> getExpenseById(Long id) {
        return repository.findById(id);
    }

    public ExpenseLedger saveExpense(ExpenseLedger expense) {
        return repository.save(expense);
    }

    public ExpenseLedger updateExpense(Long id, ExpenseLedger expenseDetails) {
        ExpenseLedger expense = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Expense record not found with id: " + id));
        
        expense.setExpenseDate(expenseDetails.getExpenseDate());
        expense.setExpenseCategory(expenseDetails.getExpenseCategory());
        expense.setAmount(expenseDetails.getAmount());
        expense.setPaidTo(expenseDetails.getPaidTo());
        expense.setApprovedBy(expenseDetails.getApprovedBy());
        expense.setNotes(expenseDetails.getNotes());

        return repository.save(expense);
    }

    public void deleteExpense(Long id) {
        repository.deleteById(id);
    }

    public List<ExpenseLedger> getExpensesByDateRange(LocalDate startDate, LocalDate endDate) {
        return repository.findByExpenseDateBetween(startDate, endDate);
    }

    public List<DailyExpenseSum> getDailyExpenseSummary(LocalDate startDate, LocalDate endDate) {
        return repository.getDailyExpenseSummary(startDate, endDate);
    }
}