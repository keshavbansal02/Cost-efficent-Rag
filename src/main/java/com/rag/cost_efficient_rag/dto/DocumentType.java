package com.rag.cost_efficient_rag.dto;

import java.util.Arrays;

/**
 * Supported document formats for RAG ingestion.
 */
public enum DocumentType {
    PDF,
    HTML,
    MARKDOWN,
    TEXT;

    /**
     * Infer or map a string representation (e.g. extension, content-type, or name) to DocumentType.
     *
     * @param type File extension, content-type, or type name
     * @return Resolved DocumentType, defaulting to TEXT if unmatched
     */
    public static DocumentType fromString(String type) {
        if (type == null || type.isBlank()) {
            return TEXT;
        }
        String normalized = type.trim().toUpperCase();
        if (normalized.endsWith(".PDF") || normalized.equals("PDF") || normalized.equals("APPLICATION/PDF")) {
            return PDF;
        }
        if (normalized.endsWith(".HTML") || normalized.endsWith(".HTM") || normalized.equals("HTML") || normalized.equals("TEXT/HTML")) {
            return HTML;
        }
        if (normalized.endsWith(".MD") || normalized.endsWith(".MARKDOWN") || normalized.equals("MARKDOWN") || normalized.equals("MD") || normalized.equals("TEXT/MARKDOWN")) {
            return MARKDOWN;
        }
        return Arrays.stream(DocumentType.values())
                .filter(t -> t.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(TEXT);
    }
}
