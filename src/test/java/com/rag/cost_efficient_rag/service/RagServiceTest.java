package com.rag.cost_efficient_rag.service;

import com.rag.cost_efficient_rag.dto.RagQueryRequest;
import com.rag.cost_efficient_rag.dto.RagQueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatModel chatModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(vectorStore, chatModel, jdbcTemplate, objectMapper, "gemini_vector_store_final");
        // Lenient stub for lexical search fallback
        lenient().when(jdbcTemplate.query(anyString(), any(Object[].class), any(org.springframework.jdbc.core.RowMapper.class)))
                 .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("Should retrieve chunks, generate grounded answer and extract citations")
    void testQuery_Success() {
        RagQueryRequest request = RagQueryRequest.builder()
                .query("What is Spring AI?")
                .topK(3)
                .build();

        Document doc1 = new Document("chunk-1", "Spring AI provides RAG abstractions.", Map.of(
                "file_name", "spring_ai_doc.pdf",
                "chunk_hash", "hash123",
                "chunk_index", 0
        ));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1));

        ChatResponse mockChatResponse = mock(ChatResponse.class);
        Generation mockGeneration = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage("Spring AI provides RAG abstractions [Doc 1].");

        when(mockGeneration.getOutput()).thenReturn(assistantMessage);
        when(mockChatResponse.getResult()).thenReturn(mockGeneration);

        ChatResponseMetadata mockMetadata = mock(ChatResponseMetadata.class);
        Usage mockUsage = mock(Usage.class);
        when(mockUsage.getPromptTokens()).thenReturn(50L);
        when(mockUsage.getGenerationTokens()).thenReturn(20L);
        when(mockUsage.getTotalTokens()).thenReturn(70L);
        when(mockMetadata.getUsage()).thenReturn(mockUsage);
        when(mockChatResponse.getMetadata()).thenReturn(mockMetadata);

        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse);

        RagQueryResponse response = ragService.query(request);

        assertThat(response).isNotNull();
        assertThat(response.isGrounded()).isTrue();
        assertThat(response.getAnswer()).contains("Spring AI provides RAG abstractions");
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getCitations()).hasSize(1);
        assertThat(response.getCitations().get(0).getFilename()).isEqualTo("spring_ai_doc.pdf");
        assertThat(response.getCitations().get(0).getSha256Hash()).isEqualTo("hash123");
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(70L);

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Should return grounded fallback when zero context chunks are found without calling LLM")
    void testQuery_NoContext_FallbackWithoutLLMCall() {
        RagQueryRequest request = RagQueryRequest.builder()
                .query("Unknown topic")
                .topK(3)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        RagQueryResponse response = ragService.query(request);

        assertThat(response).isNotNull();
        assertThat(response.isGrounded()).isFalse();
        assertThat(response.getAnswer()).isEqualTo(RagService.NO_CONTEXT_FALLBACK);
        assertThat(response.getRetrievedChunkCount()).isEqualTo(0);
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(0L);

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when query is blank")
    void testQuery_BlankQuery_ThrowsException() {
        RagQueryRequest request = RagQueryRequest.builder().query("   ").build();

        assertThatThrownBy(() -> ragService.query(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query string cannot be null or blank");
    }

    @Test
    @DisplayName("Should merge and rank documents correctly using RRF")
    void testRrfRerank_Success() {
        Document doc1 = new Document("id1", "Content 1", Map.of());
        Document doc2 = new Document("id2", "Content 2", Map.of());
        Document doc3 = new Document("id3", "Content 3", Map.of());

        // Dense Vector returns: [doc1, doc2]
        List<Document> semanticDocs = List.of(doc1, doc2);
        // Lexical FTS returns: [doc3, doc1]
        List<Document> lexicalDocs = List.of(doc3, doc1);

        List<Document> result = ragService.rrfRerank(semanticDocs, lexicalDocs, 3);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getId()).isEqualTo("id1");
    }
}
