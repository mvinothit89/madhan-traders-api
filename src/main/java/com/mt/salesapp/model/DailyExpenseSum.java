package com.mt.salesapp.model;

import java.time.LocalDate;

public interface DailyExpenseSum {
    LocalDate getExpenseDate();
    Double getTotalAmount();
}