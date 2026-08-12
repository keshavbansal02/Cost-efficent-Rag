package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing cost projection metrics for a specific scale tier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostTierProjection {

    private String scaleTier;
    private long vectorCount;
    private double storageGb;
    private double pgVectorMonthlyCostUsd;
    private double managedDbMonthlyCostUsd;
    private double monthlySavingsUsd;
    private double savingsPercentage;
    private String details;
}
