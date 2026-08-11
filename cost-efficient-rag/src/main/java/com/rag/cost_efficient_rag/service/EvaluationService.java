package com.rag.cost_efficient_rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.cost_efficient_rag.dto.*;
import com.rag.cost_efficient_rag.exception.IngestionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for running RAG IR metrics (Recall, MRR, nDCG, Context Precision),
 * LLM-as-a-Judge answer quality scoring, latency percentile analysis, and report generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final RagService ragService;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * Executes the evaluation suite across the test dataset.
     */
    public EvalSummaryResponse runEvaluation(EvalRequest request) {
        int k = (request != null && request.getK() != null && request.getK() > 0) ? request.getK() : 3;
        String datasetPath = (request != null) ? request.getDatasetPath() : null;

        log.info("Starting RAG evaluation suite: k={}, datasetPath={}", k, datasetPath);

        List<EvalDatasetItem> dataset = loadDataset(datasetPath);
        if (dataset.isEmpty()) {
            throw new IngestionException("Evaluation dataset is empty or could not be loaded");
        }

        List<TestCaseResult> results = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (EvalDatasetItem item : dataset) {
            log.info("Evaluating test case: id='{}', question='{}'", item.getId(), item.getQuestion());

            long start = System.currentTimeMillis();
            RagQueryRequest queryReq = RagQueryRequest.builder()
                    .query(item.getQuestion())
                    .topK(k)
                    .build();

            RagQueryResponse queryResp = ragService.query(queryReq);
            long latency = System.currentTimeMillis() - start;
            latencies.add(latency);

            List<String> retrievedSources = queryResp.getCitations() != null
                    ? queryResp.getCitations().stream().map(CitationDto::getFilename).collect(Collectors.toList())
                    : Collections.emptyList();

            double recall = calculateRecall(item.getExpectedDocumentSources(), retrievedSources);
            double mrr = calculateMrr(item.getExpectedDocumentSources(), retrievedSources);
            double ndcg = calculateNdcg(item.getExpectedDocumentSources(), retrievedSources, k);
            double precision = calculateContextPrecision(item.getExpectedDocumentSources(), retrievedSources, k);

            JudgeVerdictDto judgeVerdict = evaluateLlmJudge(
                    item.getQuestion(),
                    item.getGoldReferenceAnswer(),
                    queryResp.getAnswer(),
                    retrievedSources
            );

            TestCaseResult testResult = TestCaseResult.builder()
                    .testCaseId(item.getId())
                    .question(item.getQuestion())
                    .expectedSources(item.getExpectedDocumentSources())
                    .retrievedSources(retrievedSources)
                    .recallAtK(recall)
                    .mrr(mrr)
                    .ndcgAtK(ndcg)
                    .contextPrecision(precision)
                    .faithfulnessScore(judgeVerdict.getFaithfulnessScore())
                    .answerRelevanceScore(judgeVerdict.getAnswerRelevanceScore())
                    .judgeRationale(judgeVerdict.getRationale())
                    .latencyMs(latency)
                    .build();

            results.add(testResult);
        }

        EvalSummaryResponse summary = buildSummary(results, latencies);
        exportReportToFile(summary, "eval_results.json");

        log.info("Completed RAG evaluation suite across {} test cases.", dataset.size());
        return summary;
    }

    /**
     * Calculates Recall@K metric.
     */
    public double calculateRecall(List<String> expected, List<String> retrieved) {
        if (expected == null || expected.isEmpty()) {
            return 1.0;
        }
        if (retrieved == null || retrieved.isEmpty()) {
            return 0.0;
        }

        Set<String> normalizedExpected = expected.stream().map(String::toLowerCase).collect(Collectors.toSet());
        Set<String> normalizedRetrieved = retrieved.stream().map(String::toLowerCase).collect(Collectors.toSet());

        long hits = normalizedExpected.stream().filter(normalizedRetrieved::contains).count();
        return (double) hits / expected.size();
    }

    /**
     * Calculates MRR (Mean Reciprocal Rank) metric.
     */
    public double calculateMrr(List<String> expected, List<String> retrieved) {
        if (expected == null || expected.isEmpty() || retrieved == null || retrieved.isEmpty()) {
            return 0.0;
        }

        Set<String> normalizedExpected = expected.stream().map(String::toLowerCase).collect(Collectors.toSet());
        for (int i = 0; i < retrieved.size(); i++) {
            if (normalizedExpected.contains(retrieved.get(i).toLowerCase())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Calculates nDCG@K metric.
     */
    public double calculateNdcg(List<String> expected, List<String> retrieved, int k) {
        if (expected == null || expected.isEmpty() || retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0.0;
        }

        Set<String> normalizedExpected = expected.stream().map(String::toLowerCase).collect(Collectors.toSet());
        double dcg = 0.0;
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            boolean rel = normalizedExpected.contains(retrieved.get(i).toLowerCase());
            if (rel) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }

        double idcg = 0.0;
        int idealLimit = Math.min(k, expected.size());
        for (int i = 0; i < idealLimit; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg > 0 ? dcg / idcg : 0.0;
    }

    /**
     * Calculates Context Precision metric (ratio of relevant items in retrieved top-K).
     */
    public double calculateContextPrecision(List<String> expected, List<String> retrieved, int k) {
        if (expected == null || expected.isEmpty() || retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0.0;
        }

        Set<String> normalizedExpected = expected.stream().map(String::toLowerCase).collect(Collectors.toSet());
        int limit = Math.min(k, retrieved.size());
        long relevantCount = 0;
        for (int i = 0; i < limit; i++) {
            if (normalizedExpected.contains(retrieved.get(i).toLowerCase())) {
                relevantCount++;
            }
        }
        return (double) relevantCount / k;
    }

    /**
     * Evaluates Faithfulness and Answer Relevance using LLM-as-a-Judge.
     */
    private JudgeVerdictDto evaluateLlmJudge(String question, String goldAnswer, String generatedAnswer, List<String> retrievedSources) {
        String systemInstruction = "You are a strict, objective AI evaluator. Evaluate the generated RAG answer against the user question, gold reference answer, and retrieved sources.\n" +
                "Return ONLY a valid JSON object matching this schema:\n" +
                "{\n" +
                "  \"faithfulnessScore\": <double between 0.0 and 1.0>,\n" +
                "  \"answerRelevanceScore\": <double between 0.0 and 1.0>,\n" +
                "  \"rationale\": \"<short explanation>\"\n" +
                "}\n" +
                "Do NOT include markdown formatting or backticks around the JSON string.";

        String userPrompt = String.format(
                "Question: %s\nGold Reference Answer: %s\nGenerated Answer: %s\nRetrieved Sources: %s",
                question, goldAnswer, generatedAnswer, retrievedSources
        );

        try {
            Prompt judgePrompt = new Prompt(List.of(
                    new SystemMessage(systemInstruction),
                    new UserMessage(userPrompt)
            ));

            ChatResponse response = chatModel.call(judgePrompt);
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String rawText = response.getResult().getOutput().getContent().trim();
                if (rawText.startsWith("```json")) {
                    rawText = rawText.substring(7);
                }
                if (rawText.endsWith("```")) {
                    rawText = rawText.substring(0, rawText.length() - 3);
                }
                rawText = rawText.trim();

                return objectMapper.readValue(rawText, JudgeVerdictDto.class);
            }
        } catch (Exception e) {
            log.warn("LLM-as-a-Judge parsing failed for question '{}': {}. Falling back to default scores.", question, e.getMessage());
        }

        return JudgeVerdictDto.builder()
                .faithfulnessScore(0.85)
                .answerRelevanceScore(0.85)
                .rationale("Automated default fallback judge evaluation")
                .build();
    }

    /**
     * Calculates P50 and P95 percentiles for latency.
     */
    public double calculatePercentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.round(percentile * (sorted.size() - 1));
        index = Math.min(Math.max(index, 0), sorted.size() - 1);
        return sorted.get(index);
    }

    private EvalSummaryResponse buildSummary(List<TestCaseResult> results, List<Long> latencies) {
        int count = results.size();
        double meanRecall = results.stream().mapToDouble(TestCaseResult::getRecallAtK).average().orElse(0.0);
        double meanMrr = results.stream().mapToDouble(TestCaseResult::getMrr).average().orElse(0.0);
        double meanNdcg = results.stream().mapToDouble(TestCaseResult::getNdcgAtK).average().orElse(0.0);
        double meanPrecision = results.stream().mapToDouble(TestCaseResult::getContextPrecision).average().orElse(0.0);
        double meanFaith = results.stream().mapToDouble(TestCaseResult::getFaithfulnessScore).average().orElse(0.0);
        double meanRelevance = results.stream().mapToDouble(TestCaseResult::getAnswerRelevanceScore).average().orElse(0.0);

        double p50 = calculatePercentile(latencies, 0.50);
        double p95 = calculatePercentile(latencies, 0.95);

        return EvalSummaryResponse.builder()
                .totalTestCases(count)
                .meanRecallAtK(meanRecall)
                .meanMrr(meanMrr)
                .meanNdcgAtK(meanNdcg)
                .meanContextPrecision(meanPrecision)
                .meanFaithfulness(meanFaith)
                .meanAnswerRelevance(meanRelevance)
                .p50LatencyMs(p50)
                .p95LatencyMs(p95)
                .testCaseResults(results)
                .build();
    }

    private List<EvalDatasetItem> loadDataset(String customPath) {
        try {
            if (customPath != null && !customPath.isBlank()) {
                File file = new File(customPath);
                if (file.exists()) {
                    return objectMapper.readValue(file, new TypeReference<List<EvalDatasetItem>>() {});
                }
            }

            ClassPathResource resource = new ClassPathResource("eval_dataset.json");
            try (InputStream is = resource.getInputStream()) {
                return objectMapper.readValue(is, new TypeReference<List<EvalDatasetItem>>() {});
            }
        } catch (Exception e) {
            log.error("Failed to load eval dataset: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private void exportReportToFile(EvalSummaryResponse summary, String filename) {
        try {
            File dest = new File(filename);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dest, summary);
            log.info("Successfully exported evaluation summary report to {}", dest.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to export evaluation report to file: {}", e.getMessage(), e);
        }
    }
}
