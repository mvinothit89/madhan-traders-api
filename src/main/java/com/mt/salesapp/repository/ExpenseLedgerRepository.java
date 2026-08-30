package com.mt.salesapp.repository;

import com.mt.salesapp.model.DailyExpenseSum;
import com.mt.salesapp.model.ExpenseLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseLedgerRepository extends JpaRepository<ExpenseLedger, Long> {

    // Search by date range
    List<ExpenseLedger> findByExpenseDateBetween(LocalDate startDate, LocalDate endDate);

    // Sum of expenses grouped by day
    @Query("SELECT e.expenseDate as expenseDate, SUM(e.amount) as totalAmount " +
           "FROM ExpenseLedger e " +
           "WHERE e.expenseDate BETWEEN :startDate AND :endDate " +
           "GROUP BY e.expenseDate " +
           "ORDER BY e.expenseDate ASC")
    List<DailyExpenseSum> getDailyExpenseSummary(@Param("startDate") LocalDate startDate, 
                                                 @Param("endDate") LocalDate endDate);
}