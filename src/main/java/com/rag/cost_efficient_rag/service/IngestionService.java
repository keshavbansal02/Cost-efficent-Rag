package com.rag.cost_efficient_rag.service;

import com.rag.cost_efficient_rag.config.RagProperties;
import com.rag.cost_efficient_rag.dto.DocumentType;
import com.rag.cost_efficient_rag.dto.IngestTextRequest;
import com.rag.cost_efficient_rag.dto.IngestionResponse;
import com.rag.cost_efficient_rag.exception.IngestionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core service for document ingestion, chunking, SHA-256 deduplication, and PgVector store insertion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagProperties ragProperties;
    private final GeminiVisionOcrService visionOcrService;

    /**
     * Ingest a document file (PDF, HTML, Markdown, or Text).
     */
    public IngestionResponse ingestFile(MultipartFile file, String overrideDocType, Integer overrideChunkSize, Integer overrideChunkOverlap) {
        if (file == null || file.isEmpty()) {
            throw new IngestionException("Uploaded file is null or empty");
        }

        String originalFilename = Optional.ofNullable(file.getOriginalFilename()).orElse("uploaded_document");
        DocumentType docType = (overrideDocType != null && !overrideDocType.isBlank())
                ? DocumentType.fromString(overrideDocType)
                : DocumentType.fromString(originalFilename);

        log.info("Processing file ingestion: name={}, type={}, size={} bytes", originalFilename, docType, file.getSize());

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new IngestionException("Failed to read bytes from uploaded file: " + originalFilename, e);
        }

        List<Document> rawDocuments = loadDocumentsFromBytes(fileBytes, originalFilename, docType);
        return processAndStoreDocuments(rawDocuments, originalFilename, overrideChunkSize, overrideChunkOverlap);
    }

    /**
     * Ingest raw text or JSON request payload.
     */
    public IngestionResponse ingestText(IngestTextRequest request) {
        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            throw new IngestionException("Ingestion request content cannot be null or blank");
        }

        String documentName = Optional.ofNullable(request.getDocumentName()).orElse("raw_text_input");
        DocumentType docType = Optional.ofNullable(request.getDocumentType()).orElse(DocumentType.TEXT);

        log.info("Processing raw text ingestion: name={}, type={}, length={} chars",
                documentName, docType, request.getContent().length());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", documentName);
        metadata.put("document_type", docType.name());
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }

        List<Document> rawDocuments;
        if (docType == DocumentType.HTML) {
            String cleanText = Jsoup.parse(request.getContent()).text();
            rawDocuments = List.of(new Document(cleanText, metadata));
        } else {
            rawDocuments = List.of(new Document(request.getContent(), metadata));
        }

        return processAndStoreDocuments(rawDocuments, documentName, request.getChunkSize(), request.getChunkOverlap());
    }

    /**
     * Reads and parses raw document bytes using Spring AI Document Readers and Jsoup.
     */
    private List<Document> loadDocumentsFromBytes(byte[] fileBytes, String filename, DocumentType docType) {
        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        try {
            switch (docType) {
                case PDF:
                    log.debug("Using PagePdfDocumentReader for file: {}", filename);
                    PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
                    List<Document> pdfDocs = pdfReader.get();

                    // Check if PDF has actual extractable text or is a scanned image
                    int totalExtractedChars = pdfDocs.stream()
                            .mapToInt(doc -> doc.getContent() != null ? doc.getContent().trim().length() : 0)
                            .sum();

                    if (totalExtractedChars < 50) {
                        log.info("PDF '{}' appears to be a scanned document (extracted {} chars). Triggering Multimodal Vision OCR...", filename, totalExtractedChars);
                        try {
                            String ocrText = visionOcrService.extractTextFromScannedPdf(fileBytes, filename);
                            if (ocrText != null && !ocrText.isBlank()) {
                                Map<String, Object> meta = new HashMap<>();
                                meta.put("file_name", filename);
                                meta.put("document_type", DocumentType.PDF.name());
                                meta.put("extracted_via", "GEMINI_MULTIMODAL_OCR");
                                return List.of(new Document(ocrText, meta));
                            }
                        } catch (Exception e) {
                            log.warn("OCR fallback failed for PDF: {}. Using standard PDFBox results.", filename, e);
                        }
                    }
                    return pdfDocs;

                case PNG:
                case JPEG:
                case JPG:
                case WEBP:
                    log.info("Detected image file: {}. Executing Multimodal Vision OCR and Table/Chart extraction...", filename);
                    String mime = "image/" + docType.name().toLowerCase();
                    if (docType == DocumentType.JPG) mime = "image/jpeg";
                    String imageText = visionOcrService.extractFromImage(fileBytes, mime);
                    Map<String, Object> imgMeta = new HashMap<>();
                    imgMeta.put("file_name", filename);
                    imgMeta.put("document_type", docType.name());
                    imgMeta.put("extracted_via", "GEMINI_MULTIMODAL_OCR");
                    return List.of(new Document(imageText, imgMeta));

                case HTML:
                    log.debug("Using Jsoup HTML parser for file: {}", filename);
                    String htmlContent = new String(fileBytes, StandardCharsets.UTF_8);
                    String extractedText = Jsoup.parse(htmlContent).text();
                    Map<String, Object> htmlMeta = Map.of(
                            "file_name", filename,
                            "document_type", DocumentType.HTML.name()
                    );
                    return List.of(new Document(extractedText, htmlMeta));

                case MARKDOWN:
                case TEXT:
                default:
                    log.debug("Using TextReader for file: {}", filename);
                    TextReader textReader = new TextReader(resource);
                    textReader.getCustomMetadata().put("file_name", filename);
                    textReader.getCustomMetadata().put("document_type", docType.name());
                    return textReader.get();
            }
        } catch (Exception e) {
            log.error("Failed to parse document: {}, type={}: {}", filename, docType, e.getMessage(), e);
            throw new IngestionException("Error reading document content for file: " + filename, e);
        }
    }

    /**
     * Chunk, compute SHA-256 hashes, deduplicate against PgVector, and persist new vector embeddings.
     */
    private IngestionResponse processAndStoreDocuments(List<Document> rawDocuments, String documentName, Integer overrideChunkSize, Integer overrideChunkOverlap) {
        int chunkSize = resolveChunkSize(overrideChunkSize);
        int chunkOverlap = resolveChunkOverlap(overrideChunkOverlap, chunkSize);

        log.info("Chunking document: name={}, chunkSize={}, chunkOverlap={}", documentName, chunkSize, chunkOverlap);

        List<Document> allChunks = new ArrayList<>();
        for (Document rawDoc : rawDocuments) {
            List<Document> chunks = chunkDocument(rawDoc, chunkSize, chunkOverlap, documentName);
            allChunks.addAll(chunks);
        }

        if (allChunks.isEmpty()) {
            log.warn("No text chunks generated for document: {}", documentName);
            return IngestionResponse.builder()
                    .success(true)
                    .message("Document processed but produced 0 text chunks")
                    .documentName(documentName)
                    .totalChunksProcessed(0)
                    .newChunksInserted(0)
                    .duplicateChunksSkipped(0)
                    .chunkHashes(Collections.emptyList())
                    .build();
        }

        List<String> allChunkIds = allChunks.stream().map(Document::getId).collect(Collectors.toList());
        Set<String> existingIds = findExistingChunkIds(allChunkIds);

        List<Document> newChunks = new ArrayList<>();
        List<String> chunkHashes = new ArrayList<>();

        for (Document chunk : allChunks) {
            String hash = (String) chunk.getMetadata().get("chunk_hash");
            chunkHashes.add(hash);

            if (!existingIds.contains(chunk.getId())) {
                newChunks.add(chunk);
            }
        }

        int totalProcessed = allChunks.size();
        int duplicatesSkipped = totalProcessed - newChunks.size();
        int newlyInserted = newChunks.size();

        log.info("Deduplication report for {}: Total chunks={}, New chunks={}, Duplicates skipped={}",
                documentName, totalProcessed, newlyInserted, duplicatesSkipped);

        if (!newChunks.isEmpty()) {
            log.info("Inserting {} new chunk vector embeddings into PgVectorStore...", newlyInserted);
            vectorStore.add(newChunks);
            log.info("Successfully inserted {} new chunk vector embeddings into PgVectorStore", newlyInserted);
        } else {
            log.info("All {} chunks for document '{}' already exist in PgVectorStore. Skipping insertion.", totalProcessed, documentName);
        }

        return IngestionResponse.builder()
                .success(true)
                .message(String.format("Successfully ingested '%s'. Processed %d chunks (%d inserted, %d duplicate skipped).",
                        documentName, totalProcessed, newlyInserted, duplicatesSkipped))
                .documentName(documentName)
                .totalChunksProcessed(totalProcessed)
                .newChunksInserted(newlyInserted)
                .duplicateChunksSkipped(duplicatesSkipped)
                .chunkHashes(chunkHashes)
                .build();
    }

    /**
     * Chunks a single Document using a word/token-based sliding window algorithm with overlap.
     */
    private List<Document> chunkDocument(Document sourceDoc, int chunkSize, int chunkOverlap, String documentName) {
        String content = sourceDoc.getContent();
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        String[] words = content.split("\\s+");
        if (words.length == 0) {
            return Collections.emptyList();
        }

        int step = chunkSize - chunkOverlap;
        if (step <= 0) {
            step = 1;
        }

        List<Document> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + chunkSize, words.length);
            String chunkText = String.join(" ", Arrays.copyOfRange(words, start, end)).trim();

            if (chunkText.isBlank()) {
                continue;
            }

            String hash = computeSha256(chunkText);
            String docId = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();

            Map<String, Object> metadata = new HashMap<>(sourceDoc.getMetadata());
            metadata.put("chunk_hash", hash);
            metadata.put("chunk_index", chunkIndex);
            metadata.put("document_name", documentName);
            metadata.put("chunk_word_count", end - start);

            Document chunkDoc = new Document(docId, chunkText, metadata);
            chunks.add(chunkDoc);
            chunkIndex++;

            if (end >= words.length) {
                break;
            }
        }

        return chunks;
    }

    /**
     * Query PgVector's `vector_store` table for existing document IDs.
     */
    private Set<String> findExistingChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Collections.emptySet();
        }

        try {
            // Partition query to prevent SQL parameter limits if huge batch
            Set<String> existingIds = new HashSet<>();
            int batchSize = 500;
            for (int i = 0; i < chunkIds.size(); i += batchSize) {
                List<String> batch = chunkIds.subList(i, Math.min(i + batchSize, chunkIds.size()));
                String inSql = String.join(",", Collections.nCopies(batch.size(), "?"));
                String sql = String.format("SELECT id FROM vector_store WHERE id IN (%s)", inSql);
                List<String> found = jdbcTemplate.query(sql, batch.toArray(new Object[0]), (rs, rowNum) -> rs.getString("id"));
                existingIds.addAll(found);
            }
            return existingIds;
        } catch (Exception e) {
            log.warn("Could not query vector_store table for existing chunk IDs (schema may not be initialized yet): {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Compute SHA-256 hex string for string content.
     */
    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IngestionException("SHA-256 algorithm not available in JVM environment", e);
        }
    }

    private int resolveChunkSize(Integer overrideSize) {
        if (overrideSize != null && overrideSize > 0) {
            return overrideSize;
        }
        return ragProperties.getChunking().getDefaultChunkSize();
    }

    private int resolveChunkOverlap(Integer overrideOverlap, int chunkSize) {
        int overlap = (overrideOverlap != null && overrideOverlap >= 0)
                ? overrideOverlap
                : ragProperties.getChunking().getDefaultChunkOverlap();
        if (overlap >= chunkSize) {
            overlap = Math.max(0, chunkSize - 1);
        }
        return overlap;
    }
}
