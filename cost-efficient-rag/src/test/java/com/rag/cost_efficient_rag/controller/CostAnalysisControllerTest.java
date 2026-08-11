package com.rag.cost_efficient_rag.controller;

import com.rag.cost_efficient_rag.dto.CostAnalysisResponse;
import com.rag.cost_efficient_rag.dto.CostTierProjection;
import com.rag.cost_efficient_rag.service.CostAnalysisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CostAnalysisController.class)
class CostAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CostAnalysisService costAnalysisService;

    @Test
    @DisplayName("GET /api/v1/cost/analysis should return 200 OK with CostAnalysisResponse")
    void testGetCostAnalysisEndpoint() throws Exception {
        CostTierProjection projection = CostTierProjection.builder()
                .scaleTier("100K")
                .vectorCount(100000L)
                .storageGb(0.8)
                .pgVectorMonthlyCostUsd(10.0)
                .managedDbMonthlyCostUsd(70.0)
                .monthlySavingsUsd(60.0)
                .savingsPercentage(85.7)
                .details("Neon starter vs Pinecone s1.x1")
                .build();

        CostAnalysisResponse response = CostAnalysisResponse.builder()
                .assumptions(Map.of("vectorDimensions", 1536))
                .projections(List.of(projection))
                .summary("PgVector provides 85% cost reduction.")
                .build();

        when(costAnalysisService.getCostAnalysis()).thenReturn(response);

        mockMvc.perform(get("/api/v1/cost/analysis")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projections[0].scaleTier").value("100K"))
                .andExpect(jsonPath("$.projections[0].savingsPercentage").value(85.7))
                .andExpect(jsonPath("$.summary").value("PgVector provides 85% cost reduction."));
    }
}
