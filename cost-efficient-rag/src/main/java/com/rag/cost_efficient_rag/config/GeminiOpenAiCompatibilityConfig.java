package com.rag.cost_efficient_rag.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fixes compatibility issues with Google Gemini's OpenAI compatibility endpoint.
 * Spring AI's OpenAiEmbeddingModel strictly requires a "usage" block to be present
 * in the embedding JSON response. Google's endpoint currently omits this. 
 * This interceptor intercepts the raw JSON response and injects a dummy usage block.
 */
@Configuration
public class GeminiOpenAiCompatibilityConfig {

    @Bean
    public RestClientCustomizer geminiUsageInterceptorCustomizer() {
        return restClientBuilder -> restClientBuilder.requestInterceptor((request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            
            // Only intercept embedding requests
            if (request.getURI().toString().contains("/embeddings") && response.getStatusCode().is2xxSuccessful()) {
                byte[] responseBytes = StreamUtils.copyToByteArray(response.getBody());
                String json = new String(responseBytes, StandardCharsets.UTF_8);
                
                // If usage is missing, inject it to satisfy Spring AI's strict validation
                if (!json.contains("\"usage\"")) {
                    int lastBrace = json.lastIndexOf('}');
                    if (lastBrace != -1) {
                        json = json.substring(0, lastBrace) + ",\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}";
                        byte[] newBody = json.getBytes(StandardCharsets.UTF_8);
                        return new CustomClientHttpResponse(newBody, response.getHeaders(), response.getStatusCode(), response.getStatusText());
                    }
                }
                
                // Return wrapped original response since the stream was consumed
                return new CustomClientHttpResponse(responseBytes, response.getHeaders(), response.getStatusCode(), response.getStatusText());
            }
            return response;
        });
    }

    private static class CustomClientHttpResponse implements ClientHttpResponse {
        private final byte[] body;
        private final HttpHeaders headers;
        private final HttpStatusCode statusCode;
        private final String statusText;

        public CustomClientHttpResponse(byte[] body, HttpHeaders headers, HttpStatusCode statusCode, String statusText) {
            this.body = body;
            this.headers = headers;
            this.statusCode = statusCode;
            this.statusText = statusText;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return statusCode;
        }

        @Override
        public String getStatusText() {
            return statusText;
        }

        @Override
        public void close() {
        }
    }
}
