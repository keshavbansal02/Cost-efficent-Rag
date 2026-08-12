package com.rag.cost_efficient_rag.controller;

import com.rag.cost_efficient_rag.dto.IngestTextRequest;
import com.rag.cost_efficient_rag.dto.IngestionResponse;
import com.rag.cost_efficient_rag.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for document and text ingestion into PgVector store.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    /**
     * Endpoint to ingest a document file (PDF, HTML, Markdown, or Text).
     *
     * @param file Uploaded document file
     * @param documentType Optional document format override (PDF, HTML, MARKDOWN, TEXT)
     * @param chunkSize Optional chunk size override
     * @param chunkOverlap Optional chunk overlap override
     * @return IngestionResponse containing process metrics and SHA-256 chunk hashes
     */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResponse> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap) {

        log.info("REST POST /api/v1/ingest/file received: file='{}', size={} bytes, docType={}, chunkSize={}, chunkOverlap={}",
                file != null ? file.getOriginalFilename() : "null",
                file != null ? file.getSize() : 0,
                documentType, chunkSize, chunkOverlap);

        IngestionResponse response = ingestionService.ingestFile(file, documentType, chunkSize, chunkOverlap);
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint to ingest raw text or JSON payload.
     *
     * @param request Ingestion payload containing content, metadata, and optional chunking options
     * @return IngestionResponse containing process metrics and SHA-256 chunk hashes
     */
    @PostMapping(value = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestionResponse> ingestText(@RequestBody IngestTextRequest request) {
        log.info("REST POST /api/v1/ingest/text received: docName='{}', docType={}",
                request != null ? request.getDocumentName() : "null",
                request != null ? request.getDocumentType() : "null");

        IngestionResponse response = ingestionService.ingestText(request);
        return ResponseEntity.ok(response);
    }
}
