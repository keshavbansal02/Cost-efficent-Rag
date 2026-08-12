package com.rag.cost_efficient_rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock
    private RagService ragService;

    @Mock
    private ChatModel chatModel;

    private EvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        evaluationService = new EvaluationService(ragService, chatModel, new ObjectMapper());
    }

    @Test
    @DisplayName("Should correctly calculate Recall@K metric")
    void testCalculateRecall() {
        List<String> expected = List.of("docA.pdf", "docB.pdf");
        List<String> retrieved = List.of("docA.pdf", "docC.pdf", "docD.pdf");

        double recall = evaluationService.calculateRecall(expected, retrieved);

        assertThat(recall).isCloseTo(0.5, offset(0.001));
    }

    @Test
    @DisplayName("Should correctly calculate MRR (Mean Reciprocal Rank) metric")
    void testCalculateMrr() {
        List<String> expected = List.of("docB.pdf");
        List<String> retrieved = List.of("docA.pdf", "docB.pdf", "docC.pdf");

        double mrr = evaluationService.calculateMrr(expected, retrieved);

        assertThat(mrr).isCloseTo(0.5, offset(0.001));
    }

    @Test
    @DisplayName("Should correctly calculate nDCG@K metric")
    void testCalculateNdcg() {
        List<String> expected = List.of("docA.pdf", "docB.pdf");
        List<String> retrieved = List.of("docA.pdf", "docC.pdf", "docB.pdf");

        double ndcg = evaluationService.calculateNdcg(expected, retrieved, 3);

        assertThat(ndcg).isGreaterThan(0.90).isLessThan(0.95);
    }

    @Test
    @DisplayName("Should correctly calculate Context Precision metric")
    void testCalculateContextPrecision() {
        List<String> expected = List.of("docA.pdf", "docB.pdf");
        List<String> retrieved = List.of("docA.pdf", "docB.pdf", "docC.pdf");

        double precision = evaluationService.calculateContextPrecision(expected, retrieved, 3);

        assertThat(precision).isCloseTo(0.6667, offset(0.01));
    }

    @Test
    @DisplayName("Should correctly compute latency percentiles P50 and P95")
    void testCalculatePercentile() {
        List<Long> latencies = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L);

        double p50 = evaluationService.calculatePercentile(latencies, 0.50);
        double p95 = evaluationService.calculatePercentile(latencies, 0.95);

        assertThat(p50).isGreaterThanOrEqualTo(500.0).isLessThanOrEqualTo(600.0);
        assertThat(p95).isGreaterThanOrEqualTo(900.0).isLessThanOrEqualTo(1000.0);
    }
}
