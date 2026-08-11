package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for RAG query requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryRequest {

    private String query;

    @Builder.Default
    private Integer topK = 3;

    @Builder.Default
    private Double similarityThreshold = 0.0;

    private String metadataFilter;
}
