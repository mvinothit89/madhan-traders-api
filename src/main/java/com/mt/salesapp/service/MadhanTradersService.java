package com.mt.salesapp.service;

import com.mt.salesapp.dto.DashboardSummaryDto;
import com.mt.salesapp.model.*;
import com.mt.salesapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MadhanTradersService {

    private final ProductRepository productRepository;
    private final WaterSalesRepository waterSalesRepository;
    private final MaterialSalesRepository materialSalesRepository;
    private final CashFlowRepository cashFlowRepository;
    private final CashCollectedRepository cashCollectedRepository;
    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;

    // --- DASHBOARD SERVICE ---
    @Transactional(readOnly = true)
    public DashboardSummaryDto getDashboardSummary() {
        Double waterRevenue = waterSalesRepository.getTotalRevenue();
        Double materialRevenue = materialSalesRepository.getTotalRevenue();
        Double waterProfit = waterSalesRepository.getTotalProfit();
        Double materialProfit = materialSalesRepository.getTotalProfit();

        Double totalInflow = cashFlowRepository.getSumByType("INFLOW");
        Double totalOutflow = cashFlowRepository.getSumByType("OUTFLOW");

        return DashboardSummaryDto.builder()
                .netRevenue(waterRevenue + materialRevenue)
                .netProfit(waterProfit + materialProfit)
                .totalInflow(totalInflow)
                .totalOutflow(totalOutflow)
                .build();
    }

    // --- PRODUCT INVENTORY ---
    public List<Product> getAllProducts() { return productRepository.findAll(); }
    public Product saveProduct(Product product) { return productRepository.save(product); }
    public void deleteProduct(Long id) { productRepository.deleteById(id); }

    // --- WATER SALES ---
    public List<WaterSalesEntry> getAllWaterSales() { return waterSalesRepository.findAll(); }
    public WaterSalesEntry saveWaterSales(WaterSalesEntry entry) { 
        // Automatically compute total if not provided by UI
        if(entry.getTotalAmount() == null && entry.getCashAmount() != null && entry.getGpayAmount() != null) {
            entry.setTotalAmount(entry.getCashAmount() + entry.getGpayAmount());
        }
        entry.setProfit(entry.getTotalCansSold() * Double.valueOf("20"));
        return waterSalesRepository.save(entry); 
    }
    public void deleteWaterSales(Long id) { waterSalesRepository.deleteById(id); }

    // --- MATERIAL SALES ---
    public List<MaterialSalesEntry> getAllMaterialSales() { return materialSalesRepository.findAll(); }
    public MaterialSalesEntry saveMaterialSales(MaterialSalesEntry entry) {
        try{
            if(entry.getTotalAmount() == null && entry.getCashAmount() != null && entry.getGpayAmount() != null) {
                entry.setTotalAmount(entry.getCashAmount() + entry.getGpayAmount());
            }
            if(entry.getProfit() == null)
                entry.setProfit(Double.valueOf("0"));
            entry.setUnitPrice(entry.getTotalAmount()/entry.getQuantity());
            return materialSalesRepository.save(entry);
            }
        catch (Exception ex ){
            ex.printStackTrace();
            return new MaterialSalesEntry();
        }
    }
    public void deleteMaterialSales(Long id) { materialSalesRepository.deleteById(id); }

    // --- CASH FLOW ---
    public List<CashFlowEntry> getAllCashFlows() { return cashFlowRepository.findAll(); }
    public CashFlowEntry saveCashFlow(CashFlowEntry entry) { return cashFlowRepository.save(entry); }
    public void deleteCashFlow(Long id) { cashFlowRepository.deleteById(id); }

    // --- CASH COLLECTED ---
    public List<CashCollectedEntry> getAllCashCollected() { return cashCollectedRepository.findAll(); }
    public CashCollectedEntry saveCashCollected(CashCollectedEntry entry) { return cashCollectedRepository.save(entry); }
    public void deleteCashCollected(Long id) { cashCollectedRepository.deleteById(id); }

    // --- CUSTOMERS & PAYMENTS ---
    public List<Customer> getAllCustomers() { return customerRepository.findAll(); }
    public Customer saveCustomer(Customer customer) { return customerRepository.save(customer); }
    
    public Payment processPayment(Payment payment) {
        // Adjust outstanding balance for the customer if they exist
        if(payment.getCustomerName() != null) {
             // Basic implementation: In a real scenario, you'd fetch by ID or Name and update the balance
             // Customer customer = customerRepository.findByName(...);
             // customer.setOutstandingBalance(customer.getOutstandingBalance() - payment.getAmount());
        }
        return paymentRepository.save(payment); 
    }
}