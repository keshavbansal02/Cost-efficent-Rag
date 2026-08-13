package com.rag.cost_efficient_rag.service;

import com.rag.cost_efficient_rag.config.RagProperties;
import com.rag.cost_efficient_rag.dto.DocumentType;
import com.rag.cost_efficient_rag.dto.IngestTextRequest;
import com.rag.cost_efficient_rag.dto.IngestionResponse;
import com.rag.cost_efficient_rag.exception.IngestionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RagProperties ragProperties;

    @Mock
    private GeminiVisionOcrService visionOcrService;

    @InjectMocks
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        RagProperties.Chunking chunking = new RagProperties.Chunking();
        chunking.setDefaultChunkSize(10);
        chunking.setDefaultChunkOverlap(2);
        lenient().when(ragProperties.getChunking()).thenReturn(chunking);
    }

    @Test
    @DisplayName("Should ingest raw text and calculate SHA-256 chunk hashes")
    void testIngestText_Success() {
        String sampleText = "Spring AI provides generic interface for vector databases and embedding models for RAG applications.";
        IngestTextRequest request = IngestTextRequest.builder()
                .content(sampleText)
                .documentName("test_text.txt")
                .documentType(DocumentType.TEXT)
                .chunkSize(5)
                .chunkOverlap(1)
                .build();

        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(Collections.emptyList());

        IngestionResponse response = ingestionService.ingestText(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getTotalChunksProcessed()).isGreaterThan(0);
        assertThat(response.getNewChunksInserted()).isEqualTo(response.getTotalChunksProcessed());
        assertThat(response.getDuplicateChunksSkipped()).isEqualTo(0);
        assertThat(response.getChunkHashes()).hasSize(response.getTotalChunksProcessed());

        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    @DisplayName("Should parse HTML document and strip tags before chunking")
    void testIngestHtml_Success() {
        String htmlContent = "<html><body><h1>Title</h1><p>This is HTML content for testing RAG ingestion.</p></body></html>";
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.html", "text/html", htmlContent.getBytes(StandardCharsets.UTF_8));

        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(Collections.emptyList());

        IngestionResponse response = ingestionService.ingestFile(file, "HTML", 5, 1);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getTotalChunksProcessed()).isGreaterThan(0);
        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    @DisplayName("Should handle Idempotent Re-Ingestion by skipping duplicate chunk hashes")
    void testIdempotentReIngestion_SkipsDuplicates() {
        String content = "Identical chunk content to test idempotency in PgVector.";
        IngestTextRequest request = IngestTextRequest.builder()
                .content(content)
                .documentName("idempotency_test.txt")
                .documentType(DocumentType.TEXT)
                .chunkSize(10)
                .chunkOverlap(2)
                .build();

        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    Object[] batchArgs = invocation.getArgument(1, Object[].class);
                    if (batchArgs != null && batchArgs.length > 0) {
                        return List.of((String) batchArgs[0]);
                    }
                    return Collections.emptyList();
                });

        IngestionResponse response = ingestionService.ingestText(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getNewChunksInserted()).isEqualTo(0);
        assertThat(response.getDuplicateChunksSkipped()).isEqualTo(response.getTotalChunksProcessed());

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("Should throw IngestionException when file is empty")
    void testIngestFile_EmptyFile_ThrowsException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> ingestionService.ingestFile(emptyFile, "TEXT", 10, 2))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("Uploaded file is null or empty");
    }

    @Test
    @DisplayName("Should ingest image file and trigger Gemini OCR")
    void testIngestFile_ImageOcr_Success() {
        MockMultipartFile imageFile = new MockMultipartFile("file", "chart.png", "image/png", "fake-image-bytes".getBytes());
        when(visionOcrService.extractFromImage(any(), anyString())).thenReturn("Transcribed chart data table content");

        IngestionResponse response = ingestionService.ingestFile(imageFile, "PNG", 100, 10);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getDocumentName()).isEqualTo("chart.png");
        verify(visionOcrService, times(1)).extractFromImage(any(), eq("image/png"));
    }
}
