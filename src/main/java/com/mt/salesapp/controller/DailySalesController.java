package com.mt.salesapp.controller;

import com.mt.salesapp.model.DailySales;
import com.mt.salesapp.service.DailySalesService;
import com.mt.salesapp.service.DailySalesSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/daily-sales")
@CrossOrigin(origins = "*") // Update for production
public class DailySalesController {

    @Autowired
    private DailySalesService service;

    @Autowired
    private DailySalesSyncService syncService;

    @GetMapping
    public List<DailySales> getAll() {
        return service.getAllSales();
    }

    @GetMapping("/{date}")
    public ResponseEntity<DailySales> getByDate(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DailySales sales = service.getSalesByDate(date);
        return sales != null ? ResponseEntity.ok(sales) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<DailySales> saveEntry(@RequestBody DailySales salesEntry) {
        DailySales saved = service.saveOrUpdateSales(salesEntry);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/sync")
    public ResponseEntity<String> syncSalesFromSource() {
        try {
            int updatedCount = syncService.syncDailySalesFromSourceTables();
            return ResponseEntity.ok("Successfully synchronized " + updatedCount + " date(s) into daily sales.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Sync failed: " + e.getMessage());
        }
    }
}