package com.rag.cost_efficient_rag.exception;

/**
 * Custom runtime exception thrown when document ingestion fails.
 */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
