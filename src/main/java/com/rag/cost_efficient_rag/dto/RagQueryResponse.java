package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object for RAG query responses containing grounded answer and citations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResponse {

    private String answer;
    private List<CitationDto> citations;
    private int retrievedChunkCount;
    private long executionLatencyMs;
    private TokenUsageDto tokenUsage;
    private boolean grounded;
    private Double confidenceScore;
}
