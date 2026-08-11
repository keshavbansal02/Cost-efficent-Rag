package com.rag.cost_efficient_rag.controller;

import com.rag.cost_efficient_rag.dto.RagQueryRequest;
import com.rag.cost_efficient_rag.dto.RagQueryResponse;
import com.rag.cost_efficient_rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for executing vector search and grounded LLM generation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    /**
     * Endpoint to execute a RAG query against PgVector store with grounded answer and citations.
     *
     * @param request Query payload
     * @return RagQueryResponse with answer, citations, and execution metrics
     */
    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RagQueryResponse> query(@RequestBody RagQueryRequest request) {
        log.info("REST POST /api/v1/rag/query received: query='{}'", request != null ? request.getQuery() : "null");
        RagQueryResponse response = ragService.query(request);
        return ResponseEntity.ok(response);
    }
}
