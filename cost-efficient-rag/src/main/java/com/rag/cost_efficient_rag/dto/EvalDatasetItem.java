package com.rag.cost_efficient_rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object representing an evaluation dataset item.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalDatasetItem {

    private String id;
    private String question;
    private List<String> expectedDocumentSources;
    private String goldReferenceAnswer;
}
