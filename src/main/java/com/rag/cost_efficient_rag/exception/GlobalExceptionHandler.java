package com.rag.cost_efficient_rag.exception;

import com.rag.cost_efficient_rag.dto.IngestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Collections;

/**
 * Controller advice for handling exceptions across the application cleanly and consistently.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<IngestionResponse> handleIngestionException(IngestionException ex) {
        log.error("Ingestion error: {}", ex.getMessage(), ex);
        IngestionResponse response = IngestionResponse.builder()
                .success(false)
                .message("Ingestion Failed: " + ex.getMessage())
                .totalChunksProcessed(0)
                .newChunksInserted(0)
                .duplicateChunksSkipped(0)
                .chunkHashes(Collections.emptyList())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<IngestionResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("Invalid argument: {}", ex.getMessage(), ex);
        IngestionResponse response = IngestionResponse.builder()
                .success(false)
                .message("Invalid Request: " + ex.getMessage())
                .totalChunksProcessed(0)
                .newChunksInserted(0)
                .duplicateChunksSkipped(0)
                .chunkHashes(Collections.emptyList())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<IngestionResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        log.error("Upload size limit exceeded: {}", ex.getMessage(), ex);
        IngestionResponse response = IngestionResponse.builder()
                .success(false)
                .message("File size limit exceeded")
                .totalChunksProcessed(0)
                .newChunksInserted(0)
                .duplicateChunksSkipped(0)
                .chunkHashes(Collections.emptyList())
                .build();
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IngestionResponse> handleGeneralException(Exception ex) {
        log.error("Unhandled internal server error: {}", ex.getMessage(), ex);
        IngestionResponse response = IngestionResponse.builder()
                .success(false)
                .message("Internal Error: " + ex.getMessage())
                .totalChunksProcessed(0)
                .newChunksInserted(0)
                .duplicateChunksSkipped(0)
                .chunkHashes(Collections.emptyList())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
