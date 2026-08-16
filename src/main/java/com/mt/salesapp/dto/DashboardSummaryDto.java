package com.mt.salesapp.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardSummaryDto {
    private Double netRevenue;
    private Double netProfit;
    private Double totalInflow;
    private Double totalOutflow;
}