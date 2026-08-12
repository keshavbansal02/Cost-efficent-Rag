package com.rag.cost_efficient_rag.service;

import com.rag.cost_efficient_rag.dto.CostAnalysisResponse;
import com.rag.cost_efficient_rag.dto.CostTierProjection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service calculating cost projections for 100K, 1M, and 10M vector scale tiers
 * comparing PgVector (Neon PostgreSQL) vs Fully Managed Vector Databases (Pinecone).
 */
@Slf4j
@Service
public class CostAnalysisService {

    /**
     * Generate cost analysis metrics and projections.
     */
    public CostAnalysisResponse getCostAnalysis() {
        log.info("Calculating cost projections for 100K, 1M, and 10M vector scale tiers...");

        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("embeddingModel", "text-embedding-3-small");
        assumptions.put("vectorDimensions", 1536);
        assumptions.put("bytesPerVectorWithIndex", 8192);
        assumptions.put("monthlyQueryVolume", 50000);
        assumptions.put("pgVectorProvider", "Neon Serverless PostgreSQL (HNSW index)");
        assumptions.put("managedVectorDbProvider", "Pinecone Standard Pod / Managed Cluster");

        List<CostTierProjection> projections = new ArrayList<>();
        projections.add(buildProjection("100K", 100_000L, 0.8, 10.0, 70.0,
                "Neon Free/Starter compute + 0.8 GB storage vs Pinecone s1.x1 pod minimum"));
        projections.add(buildProjection("1M", 1_000_000L, 8.0, 35.0, 280.0,
                "Neon Scale tier (1-2 vCPU + 8 GB storage) vs Pinecone 4x s1/p1 pods"));
        projections.add(buildProjection("10M", 10_000_000L, 80.0, 150.0, 1600.0,
                "Neon Compute (4 vCPU + 80 GB storage + HNSW RAM) vs Pinecone multi-pod cluster"));

        String summary = "PgVector on Cloud Neon PostgreSQL provides an average 85% to 90%+ cost reduction compared to dedicated managed vector databases across all scale tiers.";

        log.info("Cost analysis calculated: 100K savings={}%, 1M savings={}%, 10M savings={}%",
                projections.get(0).getSavingsPercentage(),
                projections.get(1).getSavingsPercentage(),
                projections.get(2).getSavingsPercentage());

        return CostAnalysisResponse.builder()
                .assumptions(assumptions)
                .projections(projections)
                .summary(summary)
                .build();
    }

    public CostTierProjection buildProjection(String scaleTier, long vectorCount, double storageGb, double pgCost, double managedCost, String details) {
        double monthlySavings = managedCost - pgCost;
        double savingsPct = Math.round(((managedCost - pgCost) / managedCost) * 1000.0) / 10.0;

        return CostTierProjection.builder()
                .scaleTier(scaleTier)
                .vectorCount(vectorCount)
                .storageGb(storageGb)
                .pgVectorMonthlyCostUsd(pgCost)
                .managedDbMonthlyCostUsd(managedCost)
                .monthlySavingsUsd(monthlySavings)
                .savingsPercentage(savingsPct)
                .details(details)
                .build();
    }
}
