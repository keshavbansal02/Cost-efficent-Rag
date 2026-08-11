package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Standardized Data Transfer Object for ingestion response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionResponse {

    private boolean success;
    private String message;
    private String documentName;
    private int totalChunksProcessed;
    private int newChunksInserted;
    private int duplicateChunksSkipped;
    private List<String> chunkHashes;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
