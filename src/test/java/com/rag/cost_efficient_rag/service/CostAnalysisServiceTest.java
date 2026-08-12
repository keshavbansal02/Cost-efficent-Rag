package com.rag.cost_efficient_rag.service;

import com.rag.cost_efficient_rag.dto.CostAnalysisResponse;
import com.rag.cost_efficient_rag.dto.CostTierProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostAnalysisServiceTest {

    private final CostAnalysisService costAnalysisService = new CostAnalysisService();

    @Test
    @DisplayName("Should generate cost analysis with 3 scale tiers (100K, 1M, 10M)")
    void testGetCostAnalysis_Success() {
        CostAnalysisResponse response = costAnalysisService.getCostAnalysis();

        assertThat(response).isNotNull();
        assertThat(response.getAssumptions()).containsKey("vectorDimensions");
        assertThat(response.getProjections()).hasSize(3);

        List<CostTierProjection> projections = response.getProjections();

        // 100K Tier
        assertThat(projections.get(0).getScaleTier()).isEqualTo("100K");
        assertThat(projections.get(0).getVectorCount()).isEqualTo(100_000L);
        assertThat(projections.get(0).getSavingsPercentage()).isGreaterThan(80.0);

        // 1M Tier
        assertThat(projections.get(1).getScaleTier()).isEqualTo("1M");
        assertThat(projections.get(1).getVectorCount()).isEqualTo(1_000_000L);
        assertThat(projections.get(1).getSavingsPercentage()).isGreaterThan(85.0);

        // 10M Tier
        assertThat(projections.get(2).getScaleTier()).isEqualTo("10M");
        assertThat(projections.get(2).getVectorCount()).isEqualTo(10_000_000L);
        assertThat(projections.get(2).getSavingsPercentage()).isGreaterThan(90.0);
    }
}
