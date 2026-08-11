package com.rag.cost_efficient_rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.cost_efficient_rag.dto.DocumentType;
import com.rag.cost_efficient_rag.dto.IngestTextRequest;
import com.rag.cost_efficient_rag.dto.IngestionResponse;
import com.rag.cost_efficient_rag.service.IngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IngestionService ingestionService;

    @Test
    @DisplayName("POST /api/v1/ingest/text should return 200 OK with IngestionResponse")
    void testIngestTextEndpoint() throws Exception {
        IngestTextRequest request = IngestTextRequest.builder()
                .content("Sample RAG text content for testing endpoint.")
                .documentName("sample.txt")
                .documentType(DocumentType.TEXT)
                .build();

        IngestionResponse response = IngestionResponse.builder()
                .success(true)
                .message("Successfully ingested 'sample.txt'. Processed 1 chunks.")
                .documentName("sample.txt")
                .totalChunksProcessed(1)
                .newChunksInserted(1)
                .duplicateChunksSkipped(0)
                .chunkHashes(List.of("hash123"))
                .build();

        when(ingestionService.ingestText(any(IngestTextRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/ingest/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.documentName").value("sample.txt"))
                .andExpect(jsonPath("$.totalChunksProcessed").value(1))
                .andExpect(jsonPath("$.newChunksInserted").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/ingest/file should return 200 OK with IngestionResponse")
    void testIngestFileEndpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "PDF file content".getBytes());

        IngestionResponse response = IngestionResponse.builder()
                .success(true)
                .message("Successfully ingested 'doc.pdf'. Processed 2 chunks.")
                .documentName("doc.pdf")
                .totalChunksProcessed(2)
                .newChunksInserted(2)
                .duplicateChunksSkipped(0)
                .chunkHashes(List.of("hash1", "hash2"))
                .build();

        when(ingestionService.ingestFile(any(), any(), any(), any())).thenReturn(response);

        mockMvc.perform(multipart("/api/v1/ingest/file")
                        .file(file)
                        .param("documentType", "PDF")
                        .param("chunkSize", "512")
                        .param("chunkOverlap", "64"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.documentName").value("doc.pdf"))
                .andExpect(jsonPath("$.totalChunksProcessed").value(2));
    }
}
