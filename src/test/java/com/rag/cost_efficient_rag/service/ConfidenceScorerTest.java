package com.rag.cost_efficient_rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfidenceScorerTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ChatModel chatModel;

    @InjectMocks
    private ConfidenceScorer confidenceScorer;

    @Test
    @DisplayName("Should return 100% confidence when RAG returns NO_CONTEXT_FALLBACK")
    void testCalculateConfidence_Fallback_ReturnsMax() {
        double score = confidenceScorer.calculateConfidence(
                "What is the salary of Keshav?",
                RagService.NO_CONTEXT_FALLBACK,
                Collections.emptyList()
        );
        assertThat(score).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Should compute positive confidence score using vector similarity and LLM judge rating")
    void testCalculateConfidence_Success() {
        Document contextDoc = new Document("id1", "Spring Boot 3.3.5 released with standard features", Map.of());
        
        float[] dummyVector = new float[768];
        dummyVector[0] = 1.0f; // Mock vector values

        when(embeddingModel.embed(anyString())).thenReturn(dummyVector);

        // Mock LLM Judge call returning alignment score of 90
        ChatResponse mockChatResponse = mock(ChatResponse.class);
        Generation mockGeneration = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage("90");
        when(mockGeneration.getOutput()).thenReturn(assistantMessage);
        when(mockChatResponse.getResult()).thenReturn(mockGeneration);
        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse);

        double score = confidenceScorer.calculateConfidence(
                "Which Spring Boot version is released?",
                "Spring Boot 3.3.5 is released.",
                List.of(contextDoc)
        );

        assertThat(score).isGreaterThan(0.0);
        assertThat(score).isLessThanOrEqualTo(100.0);
        verify(embeddingModel, times(2)).embed(anyString());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Should return 0.0 confidence when answer is empty or null")
    void testCalculateConfidence_EmptyAnswer_ReturnsZero() {
        double score = confidenceScorer.calculateConfidence(
                "query",
                "   ",
                Collections.emptyList()
        );
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should gracefully handle non-numeric LLM response by using fallback values")
    void testCalculateConfidence_NonNumericLlmResponse_FallbackScore() {
        Document contextDoc = new Document("id1", "Spring Boot 3.3.5 released with standard features", Map.of());
        
        float[] dummyVector = new float[768];
        when(embeddingModel.embed(anyString())).thenReturn(dummyVector);

        // Mock LLM returning text instead of digits (e.g. "no score")
        ChatResponse mockChatResponse = mock(ChatResponse.class);
        Generation mockGeneration = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage("Alignment score is low");
        when(mockGeneration.getOutput()).thenReturn(assistantMessage);
        when(mockChatResponse.getResult()).thenReturn(mockGeneration);
        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse);

        double score = confidenceScorer.calculateConfidence(
                "Which Spring Boot version is released?",
                "Spring Boot 3.3.5 is released.",
                List.of(contextDoc)
        );

        // Should fallback to default judge score (80.0) without throwing NumberFormatException
        assertThat(score).isGreaterThan(0.0);
    }
}
