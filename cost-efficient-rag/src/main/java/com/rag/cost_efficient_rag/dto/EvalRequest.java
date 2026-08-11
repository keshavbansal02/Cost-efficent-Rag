package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for triggering evaluation suite runs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRequest {

    @Builder.Default
    private Integer k = 3;

    private String datasetPath;
}
