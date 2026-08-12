package com.rag.cost_efficient_rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Cost-Efficient RAG application.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Chunking chunking = new Chunking();

    @Getter
    @Setter
    public static class Chunking {
        private int defaultChunkSize = 512;
        private int defaultChunkOverlap = 64;
    }
}
