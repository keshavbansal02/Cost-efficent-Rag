package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object representing the complete cost comparison analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostAnalysisResponse {

    private Map<String, Object> assumptions;
    private List<CostTierProjection> projections;
    private String summary;
}
