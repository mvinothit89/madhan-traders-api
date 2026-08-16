package com.mt.salesapp.controller;

import com.mt.salesapp.dto.DashboardSummaryDto;
import com.mt.salesapp.model.*;
import com.mt.salesapp.service.MadhanTradersService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Allows the HTML to fetch data if hosted on a different port
public class MadhanTradersController {

    private final MadhanTradersService service;

    // --- DASHBOARD API ---
    @GetMapping("/dashboard/summary")
    public ResponseEntity<DashboardSummaryDto> getSummary() {
        return ResponseEntity.ok(service.getDashboardSummary());
    }

    // --- PRODUCTS API ---
    @GetMapping("/products")
    public ResponseEntity<List<Product>> getProducts() {
        return ResponseEntity.ok(service.getAllProducts());
    }

    @PostMapping("/products")
    public ResponseEntity<Product> saveProduct(@RequestBody Product product) {
        return ResponseEntity.ok(service.saveProduct(product));
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        service.deleteProduct(id);
        return ResponseEntity.ok().build();
    }

    // --- WATER SALES API ---
    @GetMapping("/water-sales")
    public ResponseEntity<List<WaterSalesEntry>> getWaterSales() {
        return ResponseEntity.ok(service.getAllWaterSales());
    }

    @PostMapping("/water-sales")
    public ResponseEntity<WaterSalesEntry> saveWaterSales(@RequestBody WaterSalesEntry entry) {
        return ResponseEntity.ok(service.saveWaterSales(entry));
    }

    @DeleteMapping("/water-sales/{id}")
    public ResponseEntity<Void> deleteWaterSales(@PathVariable Long id) {
        service.deleteWaterSales(id);
        return ResponseEntity.ok().build();
    }

    // --- MATERIAL SALES API ---
    @GetMapping("/material-sales")
    public ResponseEntity<List<MaterialSalesEntry>> getMaterialSales() {
        return ResponseEntity.ok(service.getAllMaterialSales());
    }

    @PostMapping("/material-sales")
    public ResponseEntity<MaterialSalesEntry> saveMaterialSales(@RequestBody MaterialSalesEntry entry) {
        return ResponseEntity.ok(service.saveMaterialSales(entry));
    }

    @DeleteMapping("/material-sales/{id}")
    public ResponseEntity<Void> deleteMaterialSales(@PathVariable Long id) {
        service.deleteMaterialSales(id);
        return ResponseEntity.ok().build();
    }

    // --- CASH FLOW API ---
    @GetMapping("/cash-flow")
    public ResponseEntity<List<CashFlowEntry>> getCashFlows() {
        return ResponseEntity.ok(service.getAllCashFlows());
    }

    @PostMapping("/cash-flow")
    public ResponseEntity<CashFlowEntry> saveCashFlow(@RequestBody CashFlowEntry entry) {
        return ResponseEntity.ok(service.saveCashFlow(entry));
    }

    @DeleteMapping("/cash-flow/{id}")
    public ResponseEntity<Void> deleteCashFlow(@PathVariable Long id) {
        service.deleteCashFlow(id);
        return ResponseEntity.ok().build();
    }

    // --- CASH COLLECTED API ---
    @GetMapping("/cash-collected")
    public ResponseEntity<List<CashCollectedEntry>> getCashCollected() {
        return ResponseEntity.ok(service.getAllCashCollected());
    }

    @PostMapping("/cash-collected")
    public ResponseEntity<CashCollectedEntry> saveCashCollected(@RequestBody CashCollectedEntry entry) {
        return ResponseEntity.ok(service.saveCashCollected(entry));
    }

    @DeleteMapping("/cash-collected/{id}")
    public ResponseEntity<Void> deleteCashCollected(@PathVariable Long id) {
        service.deleteCashCollected(id);
        return ResponseEntity.ok().build();
    }

    // --- CUSTOMERS & PAYMENTS API ---
    @GetMapping("/customers")
    public ResponseEntity<List<Customer>> getCustomers() {
        return ResponseEntity.ok(service.getAllCustomers());
    }

    @PostMapping("/customers")
    public ResponseEntity<Customer> saveCustomer(@RequestBody Customer customer) {
        return ResponseEntity.ok(service.saveCustomer(customer));
    }

    @PostMapping("/payments")
    public ResponseEntity<Payment> savePayment(@RequestBody Payment payment) {
        return ResponseEntity.ok(service.processPayment(payment));
    }
}