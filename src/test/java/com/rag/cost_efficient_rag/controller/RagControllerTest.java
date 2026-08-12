package com.rag.cost_efficient_rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.cost_efficient_rag.dto.CitationDto;
import com.rag.cost_efficient_rag.dto.RagQueryRequest;
import com.rag.cost_efficient_rag.dto.RagQueryResponse;
import com.rag.cost_efficient_rag.dto.TokenUsageDto;
import com.rag.cost_efficient_rag.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagController.class)
class RagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RagService ragService;

    @Test
    @DisplayName("POST /api/v1/rag/query should return 200 OK with RagQueryResponse")
    void testQueryEndpoint() throws Exception {
        RagQueryRequest request = RagQueryRequest.builder()
                .query("How to implement cost efficient RAG?")
                .topK(3)
                .build();

        CitationDto citation = CitationDto.builder()
                .filename("rag_architecture.pdf")
                .chunkId("chunk-101")
                .sha256Hash("hash999")
                .chunkIndex(1)
                .snippet("Cost efficient RAG uses chunk deduplication...")
                .build();

        RagQueryResponse response = RagQueryResponse.builder()
                .answer("Cost efficient RAG uses chunk deduplication [Doc 1].")
                .citations(List.of(citation))
                .retrievedChunkCount(1)
                .executionLatencyMs(150L)
                .tokenUsage(new TokenUsageDto(40L, 15L, 55L))
                .grounded(true)
                .build();

        when(ragService.query(any(RagQueryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Cost efficient RAG uses chunk deduplication [Doc 1]."))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.retrievedChunkCount").value(1))
                .andExpect(jsonPath("$.citations[0].filename").value("rag_architecture.pdf"))
                .andExpect(jsonPath("$.citations[0].sha256Hash").value("hash999"))
                .andExpect(jsonPath("$.tokenUsage.totalTokens").value(55));
    }
}
