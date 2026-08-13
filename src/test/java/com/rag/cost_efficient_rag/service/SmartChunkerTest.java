package com.rag.cost_efficient_rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartChunkerTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @InjectMocks
    private SmartChunker smartChunker;

    @Test
    @DisplayName("Should isolate markdown tables and parse them as separate undivided chunks")
    void testSmartChunking_TableAware() {
        String markdownWithTable = "# Executive Summary\n" +
                "Here is the financial breakdown for the Q3 vector pod migration:\n\n" +
                "| Scale Tier | Vector Count | Neon Cost | Pinecone Cost |\n" +
                "|---|---|---|---|\n" +
                "| 100K | 100,000 | $10.00 | $70.00 |\n" +
                "| 1M | 1,000,000 | $35.00 | $280.00 |\n\n" +
                "This indicates 87.5% net monthly savings.";

        Document sourceDoc = new Document(markdownWithTable, Map.of("file_name", "finance.md"));

        // Mock sentence embeddings
        float[] dummyVector = new float[768];
        Embedding res = new Embedding(dummyVector, 0);
        EmbeddingResponse resp = new EmbeddingResponse(List.of(res));
        lenient().when(embeddingModel.call(any())).thenReturn(resp);

        List<Document> chunks = smartChunker.chunk(sourceDoc, 500, 50, "finance.md");

        assertThat(chunks).isNotEmpty();
        
        // At least one chunk must be identified as table
        boolean hasTableChunk = chunks.stream()
                .anyMatch(chunk -> Boolean.TRUE.equals(chunk.getMetadata().get("is_table")));
        
        assertThat(hasTableChunk).isTrue();

        Document tableChunk = chunks.stream()
                .filter(chunk -> Boolean.TRUE.equals(chunk.getMetadata().get("is_table")))
                .findFirst()
                .orElseThrow();
        
        assertThat(tableChunk.getContent()).contains("| Scale Tier |");
        assertThat(tableChunk.getContent()).contains("| 1M |");
    }

    @Test
    @DisplayName("Should split documents cleanly based on Markdown headers (Section-Aware)")
    void testSmartChunking_SectionAware() {
        String multiSectionText = "# Introduction\n" +
                "This is the introduction section containing background information on RAG systems.\n" +
                "# Technical Architecture\n" +
                "This section outlines PgVector store connections, HNSW indexing parameters, and REST Controllers.";

        Document sourceDoc = new Document(multiSectionText, Map.of("file_name", "architecture.md"));

        // Mock sentence embeddings
        float[] dummyVector = new float[768];
        Embedding res = new Embedding(dummyVector, 0);
        EmbeddingResponse resp = new EmbeddingResponse(List.of(res));
        lenient().when(embeddingModel.call(any())).thenReturn(resp);

        List<Document> chunks = smartChunker.chunk(sourceDoc, 500, 50, "architecture.md");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).contains("# Introduction");
        assertThat(chunks.get(0).getContent()).doesNotContain("# Technical Architecture");
        assertThat(chunks.get(1).getContent()).contains("# Technical Architecture");
    }

    @Test
    @DisplayName("Should handle empty or blank document content gracefully")
    void testSmartChunking_EmptyContent() {
        Document sourceDoc = new Document("", Map.of("file_name", "empty.md"));
        List<Document> chunks = smartChunker.chunk(sourceDoc, 500, 50, "empty.md");
        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("Should fallback to pure chunking when no headers or tables exist")
    void testSmartChunking_NoHeadersOrTables() {
        String plainText = "This is a simple plain text document with no special markdown symbols. It contains normal sentences. It does not have table structures.";
        Document sourceDoc = new Document(plainText, Map.of("file_name", "plain.txt"));

        // Mock sentence embeddings
        float[] dummyVector = new float[768];
        Embedding res = new Embedding(dummyVector, 0);
        EmbeddingResponse resp = new EmbeddingResponse(List.of(res));
        lenient().when(embeddingModel.call(any())).thenReturn(resp);

        List<Document> chunks = smartChunker.chunk(sourceDoc, 100, 10, "plain.txt");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getContent()).contains("This is a simple plain text");
    }
}
