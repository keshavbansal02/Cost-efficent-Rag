package com.rag.cost_efficient_rag.service;

import com.rag.cost_efficient_rag.dto.CitationDto;
import com.rag.cost_efficient_rag.dto.RagQueryRequest;
import com.rag.cost_efficient_rag.dto.RagQueryResponse;
import com.rag.cost_efficient_rag.dto.TokenUsageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Core Service executing vector similarity search, grounded context prompting, citations, and LLM answer generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    public static final String NO_CONTEXT_FALLBACK = "No relevant context found in stored documents";

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    /**
     * Execute RAG query against PgVectorStore and generate grounded response.
     */
    public RagQueryResponse query(RagQueryRequest request) {
        long startTime = System.currentTimeMillis();

        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("Query string cannot be null or blank");
        }

        int topK = (request.getTopK() != null && request.getTopK() > 0) ? request.getTopK() : 3;
        double threshold = (request.getSimilarityThreshold() != null) ? request.getSimilarityThreshold() : 0.0;

        log.info("Executing RAG search: query='{}', topK={}, threshold={}, filter='{}'",
                request.getQuery(), topK, threshold, request.getMetadataFilter());

        SearchRequest searchRequest = SearchRequest.query(request.getQuery()).withTopK(topK);
        if (threshold > 0.0) {
            searchRequest = searchRequest.withSimilarityThreshold(threshold);
        }
        if (request.getMetadataFilter() != null && !request.getMetadataFilter().isBlank()) {
            searchRequest = searchRequest.withFilterExpression(request.getMetadataFilter());
        }

        List<Document> retrievedDocs;
        try {
            retrievedDocs = vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.error("Error performing similarity search in PgVectorStore: {}", e.getMessage(), e);
            retrievedDocs = Collections.emptyList();
        }

        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            log.warn("Zero context chunks retrieved for query: '{}'. Returning grounded fallback answer.", request.getQuery());
            long latencyMs = System.currentTimeMillis() - startTime;
            return RagQueryResponse.builder()
                    .answer(NO_CONTEXT_FALLBACK)
                    .citations(Collections.emptyList())
                    .retrievedChunkCount(0)
                    .executionLatencyMs(latencyMs)
                    .tokenUsage(new TokenUsageDto(0, 0, 0))
                    .grounded(false)
                    .build();
        }

        log.info("Retrieved {} relevant chunk documents from PgVectorStore", retrievedDocs.size());

        List<CitationDto> citations = extractCitations(retrievedDocs);
        String systemPromptContent = buildGroundedSystemPrompt(retrievedDocs);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPromptContent),
                new UserMessage(request.getQuery())
        ));

        log.info("Invoking LLM for grounded generation...");
        ChatResponse chatResponse = chatModel.call(prompt);

        String answer = (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null)
                ? chatResponse.getResult().getOutput().getContent()
                : NO_CONTEXT_FALLBACK;

        TokenUsageDto tokenUsage = extractTokenUsage(chatResponse);
        long latencyMs = System.currentTimeMillis() - startTime;

        log.info("LLM generation completed in {} ms. Tokens used: {}", latencyMs, tokenUsage);

        return RagQueryResponse.builder()
                .answer(answer)
                .citations(citations)
                .retrievedChunkCount(retrievedDocs.size())
                .executionLatencyMs(latencyMs)
                .tokenUsage(tokenUsage)
                .grounded(true)
                .build();
    }

    /**
     * Constructs a strict, grounded system prompt containing retrieved context chunks and citation guidelines.
     */
    private String buildGroundedSystemPrompt(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a strict, facts-only AI assistant.\n")
          .append("Answer the user's question ONLY using the factual context provided below.\n")
          .append("Do NOT extrapolate, infer, or use outside knowledge. ")
          .append("If the provided context does not contain enough information to answer the question, state exactly: \"")
          .append(NO_CONTEXT_FALLBACK)
          .append("\".\n\n")
          .append("Always cite your sources by referencing the Document Index or Filename [e.g., [Doc 1, filename]].\n\n")
          .append("Retrieved Context:\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String filename = meta.getOrDefault("file_name", meta.getOrDefault("document_name", "unknown")).toString();
            String chunkHash = meta.getOrDefault("chunk_hash", "N/A").toString();

            sb.append(String.format("[Doc %d] (File: %s, Chunk ID: %s, SHA256: %s):\n",
                    (i + 1), filename, doc.getId(), chunkHash));
            sb.append(doc.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Extract structured citation objects from retrieved document metadata.
     */
    private List<CitationDto> extractCitations(List<Document> documents) {
        List<CitationDto> citations = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String filename = meta.getOrDefault("file_name", meta.getOrDefault("document_name", "unknown")).toString();
            String sha256Hash = meta.getOrDefault("chunk_hash", "").toString();
            Integer chunkIndex = meta.get("chunk_index") instanceof Number
                    ? ((Number) meta.get("chunk_index")).intValue()
                    : null;
            Double distance = meta.get("distance") instanceof Number
                    ? ((Number) meta.get("distance")).doubleValue()
                    : null;

            String snippet = doc.getContent();
            if (snippet != null && snippet.length() > 200) {
                snippet = snippet.substring(0, 200) + "...";
            }

            citations.add(CitationDto.builder()
                    .filename(filename)
                    .chunkId(doc.getId())
                    .sha256Hash(sha256Hash)
                    .chunkIndex(chunkIndex)
                    .snippet(snippet)
                    .distance(distance)
                    .build());
        }
        return citations;
    }

    /**
     * Extracts token usage stats from Spring AI ChatResponse metadata.
     */
    private TokenUsageDto extractTokenUsage(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return new TokenUsageDto(0, 0, 0);
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return new TokenUsageDto(0, 0, 0);
        }
        long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        long genTokens = usage.getGenerationTokens() != null ? usage.getGenerationTokens() : 0;
        long totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;

        return new TokenUsageDto(promptTokens, genTokens, totalTokens);
    }
}
