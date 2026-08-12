package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing a document source citation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDto {

    private String filename;
    private String chunkId;
    private String sha256Hash;
    private Integer chunkIndex;
    private String snippet;
    private Double distance;
}
