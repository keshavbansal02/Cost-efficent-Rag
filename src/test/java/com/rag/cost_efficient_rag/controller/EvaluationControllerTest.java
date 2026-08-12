package com.rag.cost_efficient_rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.cost_efficient_rag.dto.EvalRequest;
import com.rag.cost_efficient_rag.dto.EvalSummaryResponse;
import com.rag.cost_efficient_rag.service.EvaluationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvaluationController.class)
class EvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EvaluationService evaluationService;

    @Test
    @DisplayName("POST /api/v1/eval/run should execute evaluation suite and return 200 OK with EvalSummaryResponse")
    void testRunEvaluationEndpoint() throws Exception {
        EvalRequest request = EvalRequest.builder().k(3).build();

        EvalSummaryResponse response = EvalSummaryResponse.builder()
                .totalTestCases(15)
                .meanRecallAtK(0.92)
                .meanMrr(0.88)
                .meanNdcgAtK(0.90)
                .meanContextPrecision(0.85)
                .meanFaithfulness(0.95)
                .meanAnswerRelevance(0.93)
                .p50LatencyMs(120.0)
                .p95LatencyMs(350.0)
                .testCaseResults(Collections.emptyList())
                .build();

        when(evaluationService.runEvaluation(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTestCases").value(15))
                .andExpect(jsonPath("$.meanRecallAtK").value(0.92))
                .andExpect(jsonPath("$.meanMrr").value(0.88))
                .andExpect(jsonPath("$.meanFaithfulness").value(0.95));
    }
}
