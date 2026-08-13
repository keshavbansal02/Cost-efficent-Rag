package com.rag.cost_efficient_rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.cost_efficient_rag.dto.CitationDto;
import com.rag.cost_efficient_rag.dto.RagQueryRequest;
import com.rag.cost_efficient_rag.dto.RagQueryResponse;
import com.rag.cost_efficient_rag.dto.TokenUsageDto;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class RagService {

    public static final String NO_CONTEXT_FALLBACK = "No relevant context found in stored documents";

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfidenceScorer confidenceScorer;
    private final String tableName;

    public RagService(VectorStore vectorStore,
                      ChatModel chatModel,
                      JdbcTemplate jdbcTemplate,
                      ObjectMapper objectMapper,
                      ConfidenceScorer confidenceScorer,
                      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.confidenceScorer = confidenceScorer;
        this.tableName = tableName;
    }

    /**
     * Execute Hybrid RAG query (Dense Vector Search + PostgreSQL Lexical/Keyword FTS) with RRF Reranking.
     */
    public RagQueryResponse query(RagQueryRequest request) {
        long startTime = System.currentTimeMillis();

        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("Query string cannot be null or blank");
        }

        int topK = (request.getTopK() != null && request.getTopK() > 0) ? request.getTopK() : 3;
        double threshold = (request.getSimilarityThreshold() != null) ? request.getSimilarityThreshold() : 0.0;

        log.info("Executing Hybrid RAG search: query='{}', topK={}, threshold={}, filter='{}'",
                request.getQuery(), topK, threshold, request.getMetadataFilter());

        // 1. Dense Semantic Search (PgVector)
        // Retrieve slightly more candidates for RRF ranking
        SearchRequest searchRequest = SearchRequest.query(request.getQuery()).withTopK(topK * 4);
        if (threshold > 0.0) {
            searchRequest = searchRequest.withSimilarityThreshold(threshold);
        }
        if (request.getMetadataFilter() != null && !request.getMetadataFilter().isBlank()) {
            searchRequest = searchRequest.withFilterExpression(request.getMetadataFilter());
        }

        List<Document> semanticDocs;
        try {
            semanticDocs = vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.error("Error performing dense similarity search: {}", e.getMessage(), e);
            semanticDocs = Collections.emptyList();
        }

        // 2. Sparse Lexical Search (Postgres FTS / Keyword matching)
        List<Document> lexicalDocs = performLexicalSearch(request.getQuery(), topK * 4);

        // 3. Reciprocal Rank Fusion (RRF) Reranking
        List<Document> rerankedDocs = rrfRerank(semanticDocs, lexicalDocs, topK);

        if (rerankedDocs.isEmpty()) {
            log.warn("Zero context chunks retrieved for query: '{}'. Returning grounded fallback answer.", request.getQuery());
            long latencyMs = System.currentTimeMillis() - startTime;
            return RagQueryResponse.builder()
                    .answer(NO_CONTEXT_FALLBACK)
                    .citations(Collections.emptyList())
                    .retrievedChunkCount(0)
                    .executionLatencyMs(latencyMs)
                    .tokenUsage(new TokenUsageDto(0, 0, 0))
                    .grounded(false)
                    .confidenceScore(100.0)
                    .build();
        }

        log.info("RRF Reranking bubbled up {} top chunks", rerankedDocs.size());

        List<CitationDto> citations = extractCitations(rerankedDocs);
        String systemPromptContent = buildGroundedSystemPrompt(rerankedDocs);

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

        double confidence = confidenceScorer.calculateConfidence(request.getQuery(), answer, rerankedDocs);

        return RagQueryResponse.builder()
                .answer(answer)
                .citations(citations)
                .retrievedChunkCount(rerankedDocs.size())
                .executionLatencyMs(latencyMs)
                .tokenUsage(tokenUsage)
                .grounded(true)
                .confidenceScore(confidence)
                .build();
    }

    /**
     * Executes native PostgreSQL Full-Text search query matching keywords.
     */
    private List<Document> performLexicalSearch(String query, int limit) {
        log.info("Executing Lexical Keyword Search for query: '{}', limit: {}", query, limit);
        try {
            // Clean non-alphanumeric characters for to_tsquery parser compatibility
            String cleanQuery = query.replaceAll("[^a-zA-Z0-9\\s]", " ").trim();
            if (cleanQuery.isBlank()) {
                return Collections.emptyList();
            }

            // Split into words and join with & logical operator
            String tsQuery = String.join(" & ", cleanQuery.split("\\s+"));

            String sql = String.format(
                    "SELECT id, content, metadata, ts_rank_cd(to_tsvector('english', content), to_tsquery('english', ?)) as rank " +
                    "FROM %s " +
                    "WHERE to_tsvector('english', content) @@ to_tsquery('english', ?) " +
                    "ORDER BY rank DESC LIMIT ?", tableName);

            return jdbcTemplate.query(sql, new Object[]{tsQuery, tsQuery, limit}, (rs, rowNum) -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String metadataJson = rs.getString("metadata");

                Map<String, Object> metadata = deserializeMetadata(metadataJson);
                return new Document(id, content, metadata);
            });
        } catch (Exception e) {
            log.warn("FTS keyword search failed (falling back to simple ILIKE search): {}", e.getMessage());
            return performIlikeSearch(query, limit);
        }
    }

    private List<Document> performIlikeSearch(String query, int limit) {
        try {
            String sql = String.format(
                    "SELECT id, content, metadata FROM %s " +
                    "WHERE content ILIKE ? LIMIT ?", tableName);
            return jdbcTemplate.query(sql, new Object[]{"%" + query + "%", limit}, (rs, rowNum) -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String metadataJson = rs.getString("metadata");

                Map<String, Object> metadata = deserializeMetadata(metadataJson);
                return new Document(id, content, metadata);
            });
        } catch (Exception e) {
            log.error("FTS and ILIKE search both failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Compute Reciprocal Rank Fusion (RRF) rank scores across dense and lexical results.
     */
    public List<Document> rrfRerank(List<Document> semanticDocs, List<Document> lexicalDocs, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // Score both ranked lists
        scoreRanks(semanticDocs, rrfScores, docMap);
        scoreRanks(lexicalDocs, rrfScores, docMap);

        // Sort by RRF score descending
        List<String> sortedIds = new ArrayList<>(rrfScores.keySet());
        sortedIds.sort((id1, id2) -> Double.compare(rrfScores.get(id2), rrfScores.get(id1)));

        List<Document> reranked = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, sortedIds.size()); i++) {
            reranked.add(docMap.get(sortedIds.get(i)));
        }
        return reranked;
    }

    private void scoreRanks(List<Document> docs, Map<String, Double> rrfScores, Map<String, Document> docMap) {
        if (docs == null) return;
        for (int rank = 0; rank < docs.size(); rank++) {
            Document doc = docs.get(rank);
            String id = doc.getId();
            docMap.put(id, doc);

            double current = rrfScores.getOrDefault(id, 0.0);
            // standard constant 60
            double rankScore = 1.0 / (60.0 + (rank + 1));
            rrfScores.put(id, current + rankScore);
        }
    }

    private Map<String, Object> deserializeMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<HashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

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

    private List<CitationDto> extractCitations(List<Document> documents) {
        List<CitationDto> citations = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String filename = meta.getOrDefault("file_name", meta.getOrDefault("document_name", "unknown")).toString();
            String chunkHash = meta.getOrDefault("chunk_hash", "N/A").toString();

            citations.add(CitationDto.builder()
                    .chunkIndex(i + 1)
                    .filename(filename)
                    .chunkId(doc.getId())
                    .sha256Hash(chunkHash)
                    .snippet(doc.getContent())
                    .build());
        }
        return citations;
    }

    private TokenUsageDto extractTokenUsage(ChatResponse chatResponse) {
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            Usage usage = chatResponse.getMetadata().getUsage();
            return new TokenUsageDto(
                    usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                    usage.getGenerationTokens() != null ? usage.getGenerationTokens() : 0,
                    usage.getTotalTokens() != null ? usage.getTotalTokens() : 0
            );
        }
        return new TokenUsageDto(0, 0, 0);
    }
}
