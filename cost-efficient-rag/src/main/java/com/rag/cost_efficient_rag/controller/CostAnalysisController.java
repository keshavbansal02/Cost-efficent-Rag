package com.rag.cost_efficient_rag.controller;

import com.rag.cost_efficient_rag.dto.CostAnalysisResponse;
import com.rag.cost_efficient_rag.service.CostAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for retrieving cost analysis projections.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cost")
@RequiredArgsConstructor
public class CostAnalysisController {

    private final CostAnalysisService costAnalysisService;

    /**
     * Endpoint to retrieve cost projections comparing PgVector vs Managed Vector DBs across 100K, 1M, and 10M vector tiers.
     *
     * @return CostAnalysisResponse containing scale tier metrics and financial savings summary
     */
    @GetMapping("/analysis")
    public ResponseEntity<CostAnalysisResponse> getCostAnalysis() {
        log.info("REST GET /api/v1/cost/analysis received");
        CostAnalysisResponse response = costAnalysisService.getCostAnalysis();
        return ResponseEntity.ok(response);
    }
}
