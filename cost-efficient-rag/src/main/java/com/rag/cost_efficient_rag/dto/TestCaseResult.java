package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object representing evaluation metrics for a single test case.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseResult {

    private String testCaseId;
    private String question;
    private List<String> expectedSources;
    private List<String> retrievedSources;
    private double recallAtK;
    private double mrr;
    private double ndcgAtK;
    private double contextPrecision;
    private double faithfulnessScore;
    private double answerRelevanceScore;
    private String judgeRationale;
    private long latencyMs;
}
