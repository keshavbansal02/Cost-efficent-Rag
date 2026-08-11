package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object representing aggregate evaluation metrics and report output.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSummaryResponse {

    private int totalTestCases;
    private double meanRecallAtK;
    private double meanMrr;
    private double meanNdcgAtK;
    private double meanContextPrecision;
    private double meanFaithfulness;
    private double meanAnswerRelevance;
    private double p50LatencyMs;
    private double p95LatencyMs;
    private List<TestCaseResult> testCaseResults;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
