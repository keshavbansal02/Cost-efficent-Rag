package com.rag.cost_efficient_rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfidenceScorer {

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;

    /**
     * Compute a holistic confidence score (0.0 to 100.0) for a RAG response
     * based on vector similarity overlap and LLM alignment judgment.
     */
    public double calculateConfidence(String query, String answer, List<Document> retrievedDocs) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        // 1. If fallback answer is triggered, confidence is 100% (hallucination successfully avoided)
        if (answer.contains(RagService.NO_CONTEXT_FALLBACK)) {
            log.info("Grounded fallback triggered. Confidence score set to 100.0%");
            return 100.0;
        }

        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return 0.0;
        }

        double vectorScore = 0.0;
        try {
            // Compute embedding of the generated answer
            float[] answerEmbedding = embeddingModel.embed(answer);
            
            // Calculate maximum cosine similarity against all retrieved docs
            double maxSimilarity = 0.0;
            for (Document doc : retrievedDocs) {
                // PgVectorStore returns retrieved document embeddings as List<Double> or float[] inside properties
                // Spring AI puts it in document metadata, or we can embed the doc content
                float[] docEmbedding = embeddingModel.embed(doc.getContent());
                double sim = cosineSimilarity(answerEmbedding, docEmbedding);
                if (sim > maxSimilarity) {
                    maxSimilarity = sim;
                }
            }
            // Normalize cosine similarity (usually ranges 0.3 - 0.9, map to 0 - 100 scale)
            vectorScore = Math.max(0.0, Math.min(100.0, (maxSimilarity - 0.3) / 0.6 * 100.0));
        } catch (Exception e) {
            log.warn("Vector similarity calculation for confidence scoring failed: {}", e.getMessage());
            vectorScore = 70.0; // fallback default
        }

        // 2. LLM-as-a-Judge Groundedness Alignment Score
        double judgeScore = 0.0;
        try {
            StringBuilder contextText = new StringBuilder();
            for (int i = 0; i < retrievedDocs.size(); i++) {
                contextText.append(String.format("[Doc %d]: %s\n\n", i + 1, retrievedDocs.get(i).getContent()));
            }

            String promptText = String.format(
                    "Analyze the groundedness of the generated answer against the source context.\n" +
                    "Rate the faithfulness/hallucination risk from 0 to 100. A score of 100 means the answer contains only facts explicitly stated in the context. A score of 0 means the answer contradicts or is entirely unsupported by the context.\n\n" +
                    "Source Context:\n%s\n" +
                    "Generated Answer:\n%s\n\n" +
                    "Output ONLY the integer value (0-100) representing the alignment score. No other commentary or explanation.",
                    contextText, answer);

            ChatResponse response = chatModel.call(new Prompt(promptText));
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String scoreStr = response.getResult().getOutput().getContent().trim().replaceAll("[^0-9]", "");
                if (!scoreStr.isBlank()) {
                    judgeScore = Double.parseDouble(scoreStr);
                }
            }
        } catch (Exception e) {
            log.warn("LLM-as-a-Judge confidence rating failed: {}", e.getMessage());
            judgeScore = 80.0; // fallback default
        }

        // 3. Holistic fusion: 30% Semantic Vector overlap + 70% LLM Groundedness check
        double finalScore = (0.3 * vectorScore) + (0.7 * judgeScore);
        double roundedScore = Math.round(finalScore * 10.0) / 10.0; // 1 decimal place

        log.info("Confidence score computed: vectorScore={}, judgeScore={}, finalFusedScore={}%",
                Math.round(vectorScore), Math.round(judgeScore), roundedScore);

        return Math.max(0.0, Math.min(100.0, roundedScore));
    }

    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
