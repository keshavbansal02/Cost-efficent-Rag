package com.rag.cost_efficient_rag.controller;

import com.rag.cost_efficient_rag.dto.EvalRequest;
import com.rag.cost_efficient_rag.dto.EvalSummaryResponse;
import com.rag.cost_efficient_rag.service.EvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for executing the RAG evaluation suite.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    /**
     * Endpoint to run the automated evaluation suite.
     *
     * @param request Optional evaluation parameters (k, datasetPath)
     * @return EvalSummaryResponse containing mean IR metrics, LLM-as-a-judge scores, latencies, and report summary
     */
    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EvalSummaryResponse> runEvaluation(@RequestBody(required = false) EvalRequest request) {
        if (request == null) {
            request = new EvalRequest();
        }
        log.info("REST POST /api/v1/eval/run received: k={}, datasetPath='{}'", request.getK(), request.getDatasetPath());
        EvalSummaryResponse response = evaluationService.runEvaluation(request);
        return ResponseEntity.ok(response);
    }
}
