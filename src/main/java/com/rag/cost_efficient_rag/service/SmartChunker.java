package com.rag.cost_efficient_rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartChunker {

    private final EmbeddingModel embeddingModel;

    // Standard cosine similarity threshold for semantic boundary detection
    private static final double SIMILARITY_THRESHOLD = 0.82;
    private static final int MIN_WORDS = 60;
    private static final int MAX_WORDS = 450;

    /**
     * Parse document into section-aware, table-aware, and semantic chunks.
     */
    public List<Document> chunk(Document sourceDoc, int defaultChunkSize, int defaultChunkOverlap, String documentName) {
        String content = sourceDoc.getContent();
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        List<Document> finalChunks = new ArrayList<>();
        int chunkIndex = 0;

        // 1. Isolate and Extract Tables (Table-Aware Ingestion)
        List<TableBlock> tables = extractTables(content);
        String textWithoutTables = removeTables(content, tables);

        // 2. Section-Aware Splitting (Header Splitter)
        List<String> sections = splitIntoSections(textWithoutTables);

        // Process sections and construct semantic chunks
        for (String section : sections) {
            if (section.trim().isBlank()) {
                continue;
            }

            List<String> semanticParagraphs = buildSemanticChunks(section);
            for (String paragraph : semanticParagraphs) {
                if (paragraph.trim().isBlank()) {
                    continue;
                }

                Document chunk = buildChunkDocument(paragraph, sourceDoc.getMetadata(), chunkIndex++, documentName);
                finalChunks.add(chunk);
            }
        }

        // Add Tables as intact undivided chunks
        for (TableBlock table : tables) {
            Document tableChunk = buildChunkDocument(table.content, sourceDoc.getMetadata(), chunkIndex++, documentName);
            tableChunk.getMetadata().put("is_table", true);
            finalChunks.add(tableChunk);
        }

        log.info("Smart Chunker generated {} total chunks (including {} table chunks) from document: {}",
                finalChunks.size(), tables.size(), documentName);

        return finalChunks;
    }

    /**
     * Splits text into sections based on markdown header tags (#, ##, ###, ####)
     */
    private List<String> splitIntoSections(String text) {
        Pattern pattern = Pattern.compile("(?m)^(#{1,6}\\s+.+)$");
        Matcher matcher = pattern.matcher(text);

        List<String> sections = new ArrayList<>();
        int lastIndex = 0;

        while (matcher.find()) {
            int start = matcher.start();
            if (start > lastIndex) {
                sections.add(text.substring(lastIndex, start).trim());
            }
            lastIndex = start;
        }

        if (lastIndex < text.length()) {
            sections.add(text.substring(lastIndex).trim());
        }

        if (sections.isEmpty() && !text.isBlank()) {
            sections.add(text.trim());
        }

        return sections;
    }

    /**
     * Isolates markdown tables from text body
     */
    private List<TableBlock> extractTables(String text) {
        List<TableBlock> tables = new ArrayList<>();
        // Simple markdown table detector regex
        Pattern tablePattern = Pattern.compile("(?m)^\\|.+?\\|[ \\t]*\\r?\\n\\|[ \\t]*[-:|]+[-:|\\s]*\\|[ \\t]*\\r?\\n(\\|.+?\\|[ \\t]*\\r?\\n)+");
        Matcher matcher = tablePattern.matcher(text);

        while (matcher.find()) {
            tables.add(new TableBlock(matcher.group(), matcher.start(), matcher.end()));
        }
        return tables;
    }

    private String removeTables(String text, List<TableBlock> tables) {
        StringBuilder sb = new StringBuilder(text);
        // Remove tables from back to front to preserve offsets
        for (int i = tables.size() - 1; i >= 0; i--) {
            TableBlock table = tables.get(i);
            sb.delete(table.start, table.end);
        }
        return sb.toString();
    }

    /**
     * Groups sentences semantically based on vector cosine similarity thresholds.
     */
    private List<String> buildSemanticChunks(String text) {
        // Split text into raw sentences using basic punctuation
        String[] rawSentences = text.split("(?<=[.!?])\\s+");
        List<String> sentences = new ArrayList<>();
        for (String s : rawSentences) {
            String trimmed = s.trim();
            if (!trimmed.isBlank()) {
                sentences.add(trimmed);
            }
        }

        if (sentences.isEmpty()) {
            return Collections.emptyList();
        }

        if (sentences.size() == 1) {
            return List.of(sentences.get(0));
        }

        // Generate batch embeddings for all sentences
        List<float[]> embeddings = new ArrayList<>();
        try {
            var response = embeddingModel.call(new org.springframework.ai.embedding.EmbeddingRequest(sentences, org.springframework.ai.embedding.EmbeddingOptionsBuilder.builder().build()));
            if (response != null && response.getResults() != null) {
                response.getResults().forEach(res -> embeddings.add(res.getOutput()));
            }
        } catch (Exception e) {
            log.warn("Batch embedding for semantic chunking failed: {}. Falling back to layout/word boundaries.", e.getMessage());
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentWordCount = 0;

        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            int sentenceWords = sentence.split("\\s+").length;

            // Enforce hard maximum limit
            if (currentWordCount + sentenceWords > MAX_WORDS && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
                currentWordCount = 0;
            }

            currentChunk.append(sentence).append(" ");
            currentWordCount += sentenceWords;

            // Check if we should split semantically before next sentence
            if (i < sentences.size() - 1 && !embeddings.isEmpty() && i < embeddings.size() - 1) {
                float[] v1 = embeddings.get(i);
                float[] v2 = embeddings.get(i + 1);
                double similarity = cosineSimilarity(v1, v2);

                // Split if similarity drops below threshold, provided minimum size is met
                if (similarity < SIMILARITY_THRESHOLD && currentWordCount >= MIN_WORDS) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentWordCount = 0;
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
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

    private Document buildChunkDocument(String content, Map<String, Object> sourceMetadata, int chunkIndex, String documentName) {
        String hash = computeSha256(content);
        String docId = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();

        Map<String, Object> metadata = new HashMap<>(sourceMetadata);
        metadata.put("chunk_hash", hash);
        metadata.put("chunk_index", chunkIndex);
        metadata.put("document_name", documentName);
        metadata.put("chunk_word_count", content.split("\\s+").length);

        return new Document(docId, content, metadata);
    }

    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest algorithm not found", e);
        }
    }

    private static class TableBlock {
        final String content;
        final int start;
        final int end;

        TableBlock(String content, int start, int end) {
            this.content = content;
            this.start = start;
            this.end = end;
        }
    }
}
