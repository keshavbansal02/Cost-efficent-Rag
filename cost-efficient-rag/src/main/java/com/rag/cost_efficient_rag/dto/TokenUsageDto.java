package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for LLM token consumption metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageDto {

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
}
