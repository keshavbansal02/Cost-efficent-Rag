package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Data Transfer Object for raw text / JSON ingestion requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestTextRequest {

    private String content;
    private DocumentType documentType;
    private String documentName;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Map<String, Object> metadata;
}
