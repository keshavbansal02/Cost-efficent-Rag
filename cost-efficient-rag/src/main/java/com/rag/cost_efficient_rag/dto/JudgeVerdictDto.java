package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing an LLM-as-a-Judge evaluation verdict.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeVerdictDto {

    private double faithfulnessScore;
    private double answerRelevanceScore;
    private String rationale;
}
